package healoworld.referral_service.domain.policy;

import healoworld.referral_service.application.event.result.SlotResultType;
import healoworld.referral_service.domain.enums.FailureReason;
import healoworld.referral_service.domain.enums.ReservationStatus;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;


public class ReservationReconciliationPolicy {

    // 실패 사유·확정 시각까지 보고 판단한다 (정합성 점검 스케줄러가 쓴다)
    // 장비 장애로 확정 뒤에 차단된 예약(BLOCKED + EQUIPMENT_FAILURE + 확정 시각 있음)의 BOOKED 슬롯은 정상 상태다.
    // 정책상 장비 중단 시 기존 BOOKED와 예약 연결을 유지하므로(검사 일정·재조정 근거), "끝난 예약이 슬롯을 잡고 있음"으로 보고
    // SLOT_BOOKED(→ 보상 ReleaseSlot)를 만들지 않는다.
    // 확정 전에(REQUESTED·HELD) 차단된 예약은 확정 시각이 없으므로, 슬롯이 그 예약으로 BOOKED면 늦게 도착한 확정이라 기존처럼 풀어 준다
    public Optional<SlotResultType> decide(UUID reservationId, ReservationStatus reservationStatus, FailureReason failureReason,
                                           LocalDateTime confirmedAt, SlotSnapshot slotSnapshot, LocalDateTime now) {
        if (keepsBookedSlotAfterEquipmentBlock(reservationStatus, failureReason, confirmedAt)) {
            return Optional.empty();
        }
        return decide(reservationId, reservationStatus, slotSnapshot, now);
    }

    private static boolean keepsBookedSlotAfterEquipmentBlock(ReservationStatus reservationStatus, FailureReason failureReason,
                                                              LocalDateTime confirmedAt) {
        return reservationStatus == ReservationStatus.BLOCKED
                && failureReason == FailureReason.EQUIPMENT_FAILURE
                && confirmedAt != null;
    }

    public Optional<SlotResultType> decide(UUID reservationId, ReservationStatus reservationStatus, SlotSnapshot slotSnapshot, LocalDateTime now) {
        switch (reservationStatus) {
            case REQUESTED -> {
                if (slotSnapshot.isUnavailable()) {
                    return Optional.of(SlotResultType.SLOTS_BLOCKED);
                }
                if (!slotSnapshot.isOwnedBy(reservationId)) {
                    return Optional.of(SlotResultType.SLOT_HOLD_FAILED);
                }
                return Optional.of(SlotResultType.SLOT_HELD);
            }

            case HELD -> {
                if (slotSnapshot.isUnavailable()) {
                    return Optional.of(SlotResultType.SLOTS_BLOCKED);
                }
                if (!slotSnapshot.isOwnedBy(reservationId)) {
                    return Optional.of(SlotResultType.HOLD_EXPIRED);
                }
                if (slotSnapshot.isBooked()) {
                    return Optional.of(SlotResultType.SLOT_BOOKED);
                }
            }

            case CONFIRMED -> {
                if (!(slotSnapshot.isOwnedBy(reservationId) && slotSnapshot.isBooked())) {
                    return Optional.of(SlotResultType.SLOTS_BLOCKED);
                }
            }

            case FAILED, BLOCKED, CANCELED -> {
                if (slotSnapshot.isBooked() && slotSnapshot.isOwnedBy(reservationId)) {
                    return Optional.of(SlotResultType.SLOT_BOOKED);
                }
            }
        }

        return Optional.empty();
    }

}
