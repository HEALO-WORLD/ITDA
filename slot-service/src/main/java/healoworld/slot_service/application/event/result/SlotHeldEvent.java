package healoworld.slot_service.application.event.result;

import healoworld.slot_service.application.event.command.HoldRequestedEvent;

import java.time.LocalDateTime;
import java.util.UUID;

// slot-results로 발행하는 HOLD 성공 결과 (Slot → Referral, key = slotId)
// slot-results 이벤트는 referralId를 담지 않는다. Referral은 reservationId로 예약·의뢰를 조회한다
public record SlotHeldEvent(
        UUID eventId,
        String eventType,
        LocalDateTime occurredAt,
        UUID slotId,
        UUID reservationId,
        LocalDateTime heldUntil
) {
    public static final String TYPE = "SlotHeld";

    public static SlotHeldEvent of(HoldRequestedEvent request, LocalDateTime heldUntil, LocalDateTime now) {
        return new SlotHeldEvent(UUID.randomUUID(), TYPE, now,
                request.slotId(), request.reservationId(), heldUntil);
    }
}
