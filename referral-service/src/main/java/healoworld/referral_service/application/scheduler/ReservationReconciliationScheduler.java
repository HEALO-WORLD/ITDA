package healoworld.referral_service.application.scheduler;

import healoworld.referral_service.application.event.result.SlotResultType;
import healoworld.referral_service.application.service.ReservationReconciliationService;
import healoworld.referral_service.domain.policy.ReservationReconciliationPolicy;
import healoworld.referral_service.domain.policy.SlotSnapshot;
import healoworld.referral_service.infrastructure.client.slot.SlotClient;
import healoworld.referral_service.infrastructure.client.slot.SlotStatusQueryRequest;
import healoworld.referral_service.infrastructure.client.slot.SlotStatusResponse;
import healoworld.referral_service.infrastructure.repository.ReconciliationTarget;
import healoworld.referral_service.infrastructure.repository.ReservationRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
    private final ReservationReconciliationPolicy policy = new ReservationReconciliationPolicy();
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

        for (ReconciliationTarget target : targets) {
            try {
                SlotStatusResponse response = slotById.get(target.slotId());
                if (response == null) {
                    log.warn("정합성 점검: 슬롯 정보 없음. reservationId={}, slotId={}", target.reservationId(), target.slotId());
                    continue;
                }
                SlotSnapshot slotSnapshot = response.toSnapshot();
                Optional<SlotResultType> type = policy.decide(target.reservationId(), target.reservationStatus(), slotSnapshot, now);
                if (type.isEmpty()) {
                    continue;
                }
                reconciliationService.reconcile(target, type.get(), slotSnapshot);

            } catch (RuntimeException e) {
                log.error("정합성 점검: 복구 실패, 다음 회차 재시도. reservationId={}, status={}", target.reservationId(), target.reservationStatus(), e);
            }
        }


        log.info("대상 작업 수={}", targets.size());

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
