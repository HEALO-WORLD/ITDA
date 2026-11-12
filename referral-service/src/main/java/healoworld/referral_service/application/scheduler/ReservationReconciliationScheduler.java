package healoworld.referral_service.application.scheduler;

import healoworld.referral_service.application.event.result.SlotResultType;
import healoworld.referral_service.application.service.ReconciliationFailureRecorder;
import healoworld.referral_service.application.service.ReservationReconciliationService;
import healoworld.referral_service.domain.enums.ReservationStatus;
import healoworld.referral_service.domain.policy.ReservationReconciliationPolicy;
import healoworld.referral_service.domain.policy.SlotSnapshot;
import healoworld.referral_service.global.exception.ErrorCode;
import healoworld.referral_service.infrastructure.client.slot.SlotClient;
import healoworld.referral_service.infrastructure.client.slot.SlotStatusQueryRequest;
import healoworld.referral_service.infrastructure.client.slot.SlotStatusResponse;
import healoworld.referral_service.infrastructure.repository.ExaminationRepository;
import healoworld.referral_service.infrastructure.repository.ReconciliationTarget;
import healoworld.referral_service.infrastructure.repository.ReservationRepository;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;
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
    // CONFIRMED + SlotsBlocked, HELD + SlotBooked 후보 중 검사가 이미 시작·완료된 예약을 회차당 한 번에 찾는다 (읽기 전용)
    private final ExaminationRepository examinationRepository;
    // 복구 성공·실패·수동 확인 전환 기록 (별도 트랜잭션, 메트릭 포함)
    private final ReconciliationFailureRecorder failureRecorder;
    private final ReservationReconciliationPolicy policy = new ReservationReconciliationPolicy();
    // 제외 로그에 남길 예약 ID 최대 개수 (전체 건수는 따로 남긴다)
    private static final int SKIPPED_LOG_ID_LIMIT = 20;
    // 사전 확인에서 걸러진 수동 확인 대상의 오류 코드 (잠금 아래 최종 확인이 던지는 업무 예외와 같은 코드)
    private static final String MANUAL_REVIEW_EXAMINATION_STARTED = ErrorCode.EXAMINATION_RESERVATION_NOT_CHANGEABLE.name();
    private final int batchSize;
    private final int lookbackHours;
    private final int staleThresholdMinutes;
    private static final UUID MIN_ID = new UUID(0L, 0L);
    private UUID staleLastId = MIN_ID;
    private UUID confirmedLastId = MIN_ID;
    private UUID endedLastId = MIN_ID;
    // 성능 측정용 메트릭: 회차 전체·단계별 소요 시간, 회차당 대상 수
    static final String RUN_METRIC = "reservation.reconciliation.run";
    static final String PHASE_METRIC = "reservation.reconciliation.phase";
    static final String TARGETS_METRIC = "reservation.reconciliation.targets";
    private final MeterRegistry meterRegistry;
    private final Timer runTimer;

    public ReservationReconciliationScheduler(
            ReservationReconciliationService reconciliationService,
            ReservationRepository reservationRepository,
            SlotClient slotClient,
            ExaminationRepository examinationRepository,
            ReconciliationFailureRecorder failureRecorder,
            MeterRegistry meterRegistry,
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
        this.failureRecorder = failureRecorder;
        this.batchSize = batchSize;
        this.lookbackHours = lookbackHours;
        this.staleThresholdMinutes = staleThresholdMinutes;
        this.meterRegistry = meterRegistry;
        this.runTimer = Timer.builder(RUN_METRIC)
                .description("정합성 점검 한 회차 전체 소요 시간")
                .register(meterRegistry);
    }

    @Scheduled(
            fixedDelayString =
                    "${itda.reservation-reconciliation.scheduler.fixed-delay-ms:60000}"
    )
    public void reconcileReservations() {
        // 조기 종료(대상 없음·slot-service 실패)나 예외가 나도 회차 전체 시간을 기록한다
        runTimer.record(this::reconcileOnce);
    }

    private void reconcileOnce() {
        LocalDateTime now = LocalDateTime.now();
        // 단계별 소요 시간(ms). 회차 끝 로그 한 줄로 남긴다
        Map<String, Long> elapsedMs = new LinkedHashMap<>();

        List<ReconciliationTarget> stale = time("query_stale", elapsedMs, () ->
                reservationRepository.findStaleInProgress(now.minusMinutes(staleThresholdMinutes), staleLastId, PageRequest.of(0, batchSize)));
        List<ReconciliationTarget> confirmed = time("query_confirmed", elapsedMs, () ->
                reservationRepository.findUpcomingConfirmed(now, confirmedLastId, PageRequest.of(0, batchSize)));
        List<ReconciliationTarget> ended = time("query_ended", elapsedMs, () ->
                reservationRepository.findRecentlyEnded(now, now.minusHours(lookbackHours), endedLastId, PageRequest.of(0, batchSize)));
        recordTargetCount("stale", stale.size());
        recordTargetCount("confirmed", confirmed.size());
        recordTargetCount("ended", ended.size());

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
            responses = time("slot_api", elapsedMs, () -> slotClient.getSlotStatuses(new SlotStatusQueryRequest(slotIds)));
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
        recordTargetCount("decisions", decisions.size());
        Set<UUID> startedExamination = time("precheck", elapsedMs, () -> findStartedExamination(decisions));

        // 3단계: 주입. 사전 확인은 잠금 없는 조회라 그 뒤에 검사가 시작될 수 있지만,
        //        SlotResultService.onSlotsBlocked()·onSlotBooked()가 의뢰 → 예약 → 검사를 잠근 뒤 다시 확인하므로 잘못 차단·확정하지 않는다
        time("inject", elapsedMs, () -> {
            inject(decisions, startedExamination);
            return null;
        });

        log.info("정합성 점검 완료. 대상 수={}, 복구 판정 수={}, 단계별 소요(ms)={}",
                targets.size(), decisions.size(), elapsedMs);
    }

    private void inject(List<Decision> decisions, Set<UUID> startedExamination) {
        for (Decision decision : decisions) {
            UUID reservationId = decision.target().reservationId();
            if (startedExamination.contains(reservationId)) {
                // HELD + SlotBooked: 확정 전에 검사가 시작·완료된 데이터 불일치라 자동 확정하지 않고 수동 확인 대상으로 남긴다
                // CONFIRMED + SlotsBlocked: 기존 정책대로 이번 회차만 건너뛴다 (아래 findStartedExamination의 경고 로그)
                if (isSlotBookedOfHeld(decision)) {
                    recordManualReviewQuietly(reservationId, decision.type());
                }
                continue;
            }
            try {
                reconciliationService.reconcile(decision.target(), decision.type(), decision.slotSnapshot());
            } catch (RuntimeException e) {
                // 복구 트랜잭션은 이미 롤백됐다. 실패를 별도 트랜잭션으로 기록하며, 업무 예외는 즉시·일시 오류는 최대 시도 뒤 수동 확인으로 전환
                recordFailureQuietly(decision, e);
                continue;
            }
            recordSuccessQuietly(reservationId);
        }
    }

    // 단계 하나의 소요 시간을 Timer(reservation.reconciliation.phase{phase=...})와 회차 로그용 맵에 남긴다. 예외가 나도 기록한다
    private <T> T time(String phase, Map<String, Long> elapsedMs, Supplier<T> body) {
        long start = System.nanoTime();
        try {
            return body.get();
        } finally {
            long nanos = System.nanoTime() - start;
            Timer.builder(PHASE_METRIC)
                    .description("정합성 점검 단계별 소요 시간")
                    .tag("phase", phase)
                    .register(meterRegistry)
                    .record(nanos, TimeUnit.NANOSECONDS);
            elapsedMs.put(phase, TimeUnit.NANOSECONDS.toMillis(nanos));
        }
    }

    // 회차당 대상 수(kind=stale·confirmed·ended)와 복구 판정 수(kind=decisions)
    private void recordTargetCount(String kind, int count) {
        DistributionSummary.builder(TARGETS_METRIC)
                .description("정합성 점검 회차당 대상 수")
                .tag("kind", kind)
                .register(meterRegistry)
                .record(count);
    }

    // CONFIRMED 예약의 SlotsBlocked 후보, HELD 예약의 SlotBooked 후보 중 의뢰의 검사가 IN_PROGRESS·COMPLETED인 예약 ID
    // 조회에 실패하면 거르지 않고 기존처럼 주입한다 (최종 확인이 잠금 아래에서 다시 막으므로 잘못된 상태 변경은 없다)
    private Set<UUID> findStartedExamination(List<Decision> decisions) {
        List<UUID> candidates = decisions.stream()
                .filter(d -> isSlotsBlockedOfConfirmed(d) || isSlotBookedOfHeld(d))
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
            log.warn("정합성 점검: 검사가 이미 시작·완료돼 자동 복구에서 제외, 담당자 확인 필요. 제외 수={}, reservationIds={}{}",
                    started.size(), shown, started.size() > shown.size() ? " 외 " + (started.size() - shown.size()) + "건" : "");
        }
        return started;
    }

    private static boolean isSlotsBlockedOfConfirmed(Decision decision) {
        return decision.type() == SlotResultType.SLOTS_BLOCKED
                && decision.target().reservationStatus() == ReservationStatus.CONFIRMED;
    }

    private static boolean isSlotBookedOfHeld(Decision decision) {
        return decision.type() == SlotResultType.SLOT_BOOKED
                && decision.target().reservationStatus() == ReservationStatus.HELD;
    }

    // 기록 실패(DB 오류 등)가 회차의 다른 예약 처리를 막지 않게 한다. 기록하지 못하면 다음 회차에 다시 판단한다
    private void recordSuccessQuietly(UUID reservationId) {
        try {
            failureRecorder.recordSuccess(reservationId);
        } catch (RuntimeException e) {
            log.error("정합성 점검: 복구 성공 기록 실패. reservationId={}", reservationId, e);
        }
    }

    private void recordFailureQuietly(Decision decision, RuntimeException failure) {
        try {
            failureRecorder.recordFailure(decision.target().reservationId(), decision.type(), failure);
        } catch (RuntimeException e) {
            log.error("정합성 점검: 복구 실패 기록 실패, 다음 회차에 다시 시도. reservationId={}, status={}, recordError={}",
                    decision.target().reservationId(), decision.target().reservationStatus(), e.toString(), failure);
        }
    }

    private void recordManualReviewQuietly(UUID reservationId, SlotResultType type) {
        try {
            failureRecorder.recordManualReview(reservationId, type, MANUAL_REVIEW_EXAMINATION_STARTED,
                    "예약 확정 전 검사가 이미 시작·완료됨 (정합성 점검 사전 확인)");
        } catch (RuntimeException e) {
            log.error("정합성 점검: 수동 확인 대상 기록 실패. reservationId={}", reservationId, e);
        }
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
