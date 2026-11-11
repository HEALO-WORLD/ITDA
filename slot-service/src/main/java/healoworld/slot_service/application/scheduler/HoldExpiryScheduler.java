package healoworld.slot_service.application.scheduler;

import healoworld.slot_service.application.service.SlotHoldExpiryService;
import healoworld.slot_service.domain.enums.SlotStatus;
import healoworld.slot_service.infrastructure.repository.ExpiredHold;
import healoworld.slot_service.infrastructure.repository.SlotRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

// 만료 시각이 지난 HOLD 슬롯을 주기적으로 찾아 해제한다.
// 목록 조회만 하고, 트랜잭션은 슬롯 한 건 단위로 SlotHoldExpiryService에서 건다
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "hold-expiry.enabled", havingValue = "true", matchIfMissing = true)
public class HoldExpiryScheduler {

    private static final int BATCH_SIZE = 100;   // 한 번에 조회하는 최대 건수

    private final SlotRepository slotRepository;
    private final SlotHoldExpiryService slotHoldExpiryService;
    private final Clock clock;

    // 100건을 꽉 채워 읽었으면 쉬지 않고 바로 다음 묶음을 처리한다. 만료가 몰려도 다음 회차까지 기다리지 않는다
    @Scheduled(fixedDelayString = "${hold-expiry.fixed-delay-ms:10000}")   // 회차 종료 후 hold-expiry.fixed-delay-ms 뒤 시작
    public void expireHolds() {
        // 회차 시작 시각으로 고정해, 처리하는 동안 새로 만료되는 HOLD는 다음 회차로 넘긴다 (회차가 끝없이 이어지지 않게)
        LocalDateTime now = LocalDateTime.now(clock);
        int total = 0;
        int expiredTotal = 0;

        while (true) {
            List<ExpiredHold> batch = slotRepository.findExpiredHolds(
                    SlotStatus.HELD, now, PageRequest.of(0, BATCH_SIZE));
            if (batch.isEmpty()) {
                break;
            }

            int expiredCount = expireBatch(batch);
            total += batch.size();
            expiredTotal += expiredCount;

            // 덜 찼으면 남은 대상이 없다. 한 건도 처리하지 못했으면 실패한 슬롯만 남은 것이라 다음 회차에 다시 시도한다
            if (batch.size() < BATCH_SIZE || expiredCount == 0) {
                break;
            }
        }

        if (total > 0) {
            log.debug("HOLD 만료 회차 완료. 대상={}, 처리={}", total, expiredTotal);
        }
    }

    private int expireBatch(List<ExpiredHold> batch) {
        int expiredCount = 0;
        for (ExpiredHold hold : batch) {
            try {
                if (slotHoldExpiryService.expire(hold.slotId(), hold.reservationId(), hold.heldUntil())) {
                    expiredCount++;
                }
            } catch (Exception e) {
                // 슬롯끼리는 순서 의존이 없으므로 한 건 실패로 회차를 중단하지 않고, 다음 회차에 다시 시도한다
                log.warn("HOLD 만료 처리 실패, 다음 회차에 재시도. slotId={}, reservationId={}",
                        hold.slotId(), hold.reservationId(), e);
            }
        }
        return expiredCount;
    }
}
