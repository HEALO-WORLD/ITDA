package healoworld.slot_service.application.service;

import healoworld.slot_service.application.dto.ReleaseOutcome;
import healoworld.slot_service.domain.enums.SlotStatus;
import healoworld.slot_service.global.exception.BaseException;
import healoworld.slot_service.global.exception.ErrorCode;
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

    // ADMIN 강제 해제: 같은 예약으로 HELD·BOOKED면 반환하고, UNAVAILABLE이면 예약 연결만 끊는다.
    // Referral과 Slot의 상태 불일치를 관리자가 정리하는 용도라, 해제할 대상이 없으면 예외로 알린다
    @Transactional
    public ReleaseOutcome forceRelease(UUID slotId, UUID reservationId) {
        if (!slotRepository.existsById(slotId)) {
            throw new BaseException(ErrorCode.SLOT_NOT_FOUND);
        }

        LocalDateTime now = LocalDateTime.now(clock);
        int released = slotRepository.releaseHoldIfHeld(slotId, reservationId, now, SlotStatus.HELD, SlotStatus.AVAILABLE)
                + slotRepository.releaseBookedSlot(slotId, reservationId, now, SlotStatus.BOOKED, SlotStatus.AVAILABLE);
        if (released == 1) {
            log.warn("관리자 슬롯 강제 해제. slotId={}, reservationId={}", slotId, reservationId);
            return ReleaseOutcome.RELEASED;
        }
        if (slotRepository.detachReservationIfUnavailable(slotId, reservationId, now, SlotStatus.UNAVAILABLE) == 1) {
            log.warn("관리자 슬롯 예약 연결 해제(UNAVAILABLE 유지). slotId={}, reservationId={}", slotId, reservationId);
            return ReleaseOutcome.DETACHED;
        }
        throw new BaseException(ErrorCode.SLOT_NOT_RELEASABLE);
    }
}
