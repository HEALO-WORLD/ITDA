package healoworld.slot_service.presentation.dto.response;

import healoworld.slot_service.domain.entity.Slot;
import healoworld.slot_service.domain.enums.SlotStatus;

import java.time.LocalDateTime;
import java.util.UUID;

// POST /api/v1/internal/slots/statuses 응답 항목
// Referral SlotStatusResponse(slotId, status, reservationId, heldUntil)와 필드 이름·형식을 맞춘다
// - status: 슬롯 상태 이름 문자열 (AVAILABLE, HELD, BOOKED, UNAVAILABLE)
// - reservationId: 점유한 예약 ID. 점유 중이 아니면 null
// - heldUntil: HOLD 만료 시각(한국 시간 LocalDateTime). HELD가 아니면 null
public record SlotStatusResponse(
        UUID slotId,
        SlotStatus status,
        UUID reservationId,
        LocalDateTime heldUntil
) {
    public static SlotStatusResponse from(Slot slot) {
        return new SlotStatusResponse(slot.getId(), slot.getStatus(), slot.getReservationId(), slot.getHeldUntil());
    }
}
