package healoworld.referral_service.application.scheduler;

import healoworld.referral_service.application.event.result.SlotResultType;
import healoworld.referral_service.application.service.ReservationReconciliationService;
import healoworld.referral_service.domain.enums.ReservationStatus;
import healoworld.referral_service.domain.policy.ReservationReconciliationPolicy;
import healoworld.referral_service.domain.policy.SlotSnapshot;
import healoworld.referral_service.infrastructure.client.slot.SlotClient;
import healoworld.referral_service.infrastructure.client.slot.SlotStatusQueryRequest;
import healoworld.referral_service.infrastructure.client.slot.SlotStatusResponse;
import healoworld.referral_service.infrastructure.repository.ExaminationRepository;
import healoworld.referral_service.infrastructure.repository.ReconciliationTarget;
import healoworld.referral_service.infrastructure.repository.ReservationRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@Component
@ConditionalOnProperty(
        prefix = "itda.reservation-reconciliation.scheduler",
        name = "enabled",
        havingValue = "true"
)
public class ReservationReconciliationScheduler {
    private final ReservationReconciliationService reconciliationService;
    private final ReservationRepository reservationRepository;
    private final SlotClient slotClient;
    // CONFIRMED 예약의 SlotsBlocked 후보 중 검사가 이미 시작·완료된 예약을 회차당 한 번에 찾는다 (읽기 전용)
    private final ExaminationRepository examinationRepository;
    private final ReservationReconciliationPolicy policy = new ReservationReconciliationPolicy();
    // 제외 로그에 남길 예약 ID 최대 개수 (전체 건수는 따로 남긴다)
    private static final int SKIPPED_LOG_ID_LIMIT = 20;
    private final int batchSize;
    private final int lookbackHours;
    private final int staleThresholdMinutes;
    private static final UUID MIN_ID = new UUID(0L, 0L);
    private UUID staleLastId = MIN_ID;
    private UUID confirmedLastId = MIN_ID;
    private UUID endedLastId = MIN_ID;

    public ReservationReconciliationScheduler(
            ReservationReconciliationService reconciliationService,
            ReservationRepository reservationRepository,
            SlotClient slotClient,
            ExaminationRepository examinationRepository,
            @Value("${itda.reservation-reconciliation.scheduler.batch-size}") int batchSize,
            @Value("${itda.reservation-reconciliation.scheduler.lookback-hours}") int lookbackHours,
            @Value("${itda.reservation-reconciliation.scheduler.stale-threshold-minutes}") int staleThresholdMinutes
    ) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize는 1 이상이어야 합니다.");
        }

        this.reconciliationService = reconciliationService;
        this.reservationRepository = reservationRepository;
        this.slotClient = slotClient;
        this.examinationRepository = examinationRepository;
        this.batchSize = batchSize;
        this.lookbackHours = lookbackHours;
        this.staleThresholdMinutes = staleThresholdMinutes;
    }

    @Scheduled(
            fixedDelayString =
                    "${itda.reservation-reconciliation.scheduler.fixed-delay-ms:60000}"
    )
    public void reconcileReservations() {
        LocalDateTime now = LocalDateTime.now();

        List<ReconciliationTarget> stale = reservationRepository.findStaleInProgress(now.minusMinutes(staleThresholdMinutes), staleLastId, PageRequest.of(0, batchSize));
        List<ReconciliationTarget> confirmed = reservationRepository.findUpcomingConfirmed(now, confirmedLastId, PageRequest.of(0, batchSize));
        List<ReconciliationTarget> ended = reservationRepository.findRecentlyEnded(now, now.minusHours(lookbackHours), endedLastId, PageRequest.of(0, batchSize));

        List<ReconciliationTarget> targets = Stream.of(stale, confirmed, ended)
                .flatMap(List::stream)
                .toList();

        List<UUID> slotIds = targets.stream().map(ReconciliationTarget::slotId).distinct().toList();

        if (targets.isEmpty()) {
            staleLastId = nextCursor(stale);
            confirmedLastId = nextCursor(confirmed);
            endedLastId = nextCursor(ended);
            return;
        }

        List<SlotStatusResponse> responses;

        try {
            responses = slotClient.getSlotStatuses(new SlotStatusQueryRequest(slotIds));
        } catch (RuntimeException e) {
            log.error("정합성 점검: slot-service 상태 조회 실패, 이번 회차 건너뜀. 대상 수={}", targets.size(), e);
            return;
        }

        staleLastId = nextCursor(stale);
        confirmedLastId = nextCursor(confirmed);
        endedLastId = nextCursor(ended);

        Map<UUID, SlotStatusResponse> slotById = responses.stream()
                .collect(Collectors.toMap(SlotStatusResponse::slotId, Function.identity(), (first, second) -> first));

        // 1단계: 예약마다 복구할 결과 이벤트를 정한다 (아직 주입하지 않는다)
        List<Decision> decisions = new ArrayList<>();
        for (ReconciliationTarget target : targets) {
            try {
                SlotStatusResponse response = slotById.get(target.slotId());
                if (response == null) {
                    log.warn("정합성 점검: 슬롯 정보 없음. reservationId={}, slotId={}", target.reservationId(), target.slotId());
                    continue;
                }
                SlotSnapshot slotSnapshot = response.toSnapshot();
                // 실패 사유·확정 시각도 넘겨, 장비 장애로 확정 뒤 차단된 예약의 BOOKED 슬롯(정책상 유지)을 풀지 않게 한다
                Optional<SlotResultType> type = policy.decide(target.reservationId(), target.reservationStatus(),
                        target.failureReason(), target.confirmedAt(), slotSnapshot, now);
                type.ifPresent(t -> decisions.add(new Decision(target, t, slotSnapshot)));
            } catch (RuntimeException e) {
                log.error("정합성 점검: 복구 실패, 다음 회차 재시도. reservationId={}, status={}", target.reservationId(), target.reservationStatus(), e);
            }
        }

        // 2단계: 검사가 이미 시작·완료된 확정 예약은 SlotsBlocked를 주입해도 최종 확인에서 건너뛰므로, 미리 빼서
        //        회차마다 반복되는 inbox 기록·잠금 트랜잭션·개별 경고 로그를 만들지 않는다 (회차당 조회 1번)
        Set<UUID> startedExamination = findStartedExamination(decisions);

        // 3단계: 주입. 사전 확인은 잠금 없는 조회라 그 뒤에 검사가 시작될 수 있지만,
        //        SlotResultService.onSlotsBlocked()가 의뢰 → 예약 → 검사를 잠근 뒤 다시 확인하므로 잘못 차단하지 않는다
        for (Decision decision : decisions) {
            if (startedExamination.contains(decision.target().reservationId())) {
                continue;
            }
            try {
                reconciliationService.reconcile(decision.target(), decision.type(), decision.slotSnapshot());
            } catch (RuntimeException e) {
                log.error("정합성 점검: 복구 실패, 다음 회차 재시도. reservationId={}, status={}",
                        decision.target().reservationId(), decision.target().reservationStatus(), e);
            }
        }


        log.info("대상 작업 수={}", targets.size());

    }

    // CONFIRMED 예약의 SlotsBlocked 후보 중 의뢰의 검사가 IN_PROGRESS·COMPLETED인 예약 ID
    // 조회에 실패하면 거르지 않고 기존처럼 주입한다 (최종 확인이 잠금 아래에서 다시 막으므로 잘못된 상태 변경은 없다)
    private Set<UUID> findStartedExamination(List<Decision> decisions) {
        List<UUID> candidates = decisions.stream()
                .filter(d -> d.type() == SlotResultType.SLOTS_BLOCKED
                        && d.target().reservationStatus() == ReservationStatus.CONFIRMED)
                .map(d -> d.target().reservationId())
                .distinct()
                .toList();
        if (candidates.isEmpty()) {
            return Set.of();
        }

        Set<UUID> started;
        try {
            started = new HashSet<>(examinationRepository.findReservationIdsWithStartedExamination(candidates));
        } catch (RuntimeException e) {
            log.error("정합성 점검: 검사 상태 사전 확인 실패, 거르지 않고 주입함. 후보 수={}", candidates.size(), e);
            return Set.of();
        }

        if (!started.isEmpty()) {
            List<UUID> shown = started.stream().limit(SKIPPED_LOG_ID_LIMIT).toList();
            log.warn("정합성 점검: 검사가 이미 시작·완료돼 SlotsBlocked 복구에서 제외, 담당자 확인 필요. 제외 수={}, reservationIds={}{}",
                    started.size(), shown, started.size() > shown.size() ? " 외 " + (started.size() - shown.size()) + "건" : "");
        }
        return started;
    }

    private record Decision(ReconciliationTarget target, SlotResultType type, SlotSnapshot slotSnapshot) {
    }

    private UUID nextCursor(List<ReconciliationTarget> result) {
        UUID lastId;
        if (result.size() < batchSize) {
            lastId = MIN_ID;
        } else {
            lastId = result.getLast().reservationId();
        }
        return lastId;
    }
}
