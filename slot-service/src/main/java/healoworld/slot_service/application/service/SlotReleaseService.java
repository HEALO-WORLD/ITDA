package healoworld.slot_service.application.service;

import healoworld.slot_service.application.dto.ReleaseOutcome;
import healoworld.slot_service.domain.enums.SlotStatus;
import healoworld.slot_service.infrastructure.repository.SlotRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.UUID;

// 슬롯 반환. 예약·의뢰 상태는 Referral이 이미 바꿨고, Slot은 해당 예약이 잡은 슬롯만 비운다.
// 모든 UPDATE는 reservation_id가 일치할 때만 실행해 다른 예약이 잡은 슬롯을 풀지 않는다
@Slf4j
@Service
@RequiredArgsConstructor
public class SlotReleaseService {

    private final SlotRepository slotRepository;
    private final Clock clock;

    // 확정 실패 보상: 같은 예약으로 HELD인 슬롯을 AVAILABLE로 되돌린다
    @Transactional
    public ReleaseOutcome releaseHold(UUID slotId, UUID reservationId) {
        LocalDateTime now = LocalDateTime.now(clock);

        if (slotRepository.releaseHoldIfHeld(slotId, reservationId, now, SlotStatus.HELD, SlotStatus.AVAILABLE) == 1) {
            return ReleaseOutcome.RELEASED;
        }

        // 확정 실패 보상인데 같은 예약으로 BOOKED면 Referral과 Slot의 판단이 어긋난 것이다.
        // 어느 쪽이 맞는지 알 수 없으므로 자동으로 풀지 않고 관리자가 확인하도록 남긴다
        slotRepository.findById(slotId)
                .filter(slot -> slot.isBookedBy(reservationId))
                .ifPresent(slot -> log.warn("확정된 슬롯에 ReleaseHold 도착, 해제하지 않음. 확인 필요. slotId={}, reservationId={}",
                        slotId, reservationId));

        return ReleaseOutcome.NOT_APPLICABLE;
    }

    // 예약 취소: 같은 예약으로 BOOKED면 AVAILABLE로 되돌리고, 장애로 UNAVAILABLE이면 예약 연결만 끊는다
    @Transactional
    public ReleaseOutcome releaseSlot(UUID slotId, UUID reservationId) {
        LocalDateTime now = LocalDateTime.now(clock);

        if (slotRepository.releaseBookedSlot(slotId, reservationId, now, SlotStatus.BOOKED, SlotStatus.AVAILABLE) == 1) {
            return ReleaseOutcome.RELEASED;
        }
        if (slotRepository.detachReservationIfUnavailable(slotId, reservationId, now, SlotStatus.UNAVAILABLE) == 1) {
            return ReleaseOutcome.DETACHED;
        }
        return ReleaseOutcome.NOT_APPLICABLE;
    }
}
