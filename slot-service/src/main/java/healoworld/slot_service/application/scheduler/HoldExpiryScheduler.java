package healoworld.slot_service.application.scheduler;

import healoworld.slot_service.application.service.SlotHoldExpiryService;
import healoworld.slot_service.domain.entity.Slot;
import healoworld.slot_service.domain.enums.SlotStatus;
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

    private static final int BATCH_SIZE = 100;   // 회차당 최대 처리 건수

    private final SlotRepository slotRepository;
    private final SlotHoldExpiryService slotHoldExpiryService;
    private final Clock clock;

    @Scheduled(fixedDelayString = "${hold-expiry.fixed-delay-ms:10000}")   // 회차 종료 후 hold-expiry.fixed-delay-ms 뒤 시작
    public void expireHolds() {
        List<Slot> expired = slotRepository.findExpiredHolds(
                SlotStatus.HELD, LocalDateTime.now(clock), PageRequest.of(0, BATCH_SIZE));
        if (expired.isEmpty()) {
            return;
        }

        int expiredCount = 0;
        for (Slot slot : expired) {
            try {
                if (slotHoldExpiryService.expire(slot.getId(), slot.getReservationId(), slot.getHeldUntil())) {
                    expiredCount++;
                }
            } catch (Exception e) {
                // 슬롯끼리는 순서 의존이 없으므로 한 건 실패로 회차를 중단하지 않고, 다음 회차에 다시 시도한다
                log.warn("HOLD 만료 처리 실패, 다음 회차에 재시도. slotId={}, reservationId={}",
                        slot.getId(), slot.getReservationId(), e);
            }
        }
        log.debug("HOLD 만료 회차 완료. 대상={}, 처리={}", expired.size(), expiredCount);
    }
}
