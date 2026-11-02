package healoworld.slot_service.application.event;

import java.time.LocalDateTime;
import java.util.UUID;

// slot-results로 발행하는 HOLD 성공 결과 (Slot → Referral, key = slotId)
public record SlotHeldEvent(
        UUID eventId,
        String eventType,
        LocalDateTime occurredAt,
        UUID slotId,
        UUID reservationId,
        UUID referralId,
        LocalDateTime heldUntil
) {
    public static final String TYPE = "SlotHeld";

    public static SlotHeldEvent of(HoldRequestedEvent request, LocalDateTime heldUntil, LocalDateTime now) {
        return new SlotHeldEvent(UUID.randomUUID(), TYPE, now,
                request.slotId(), request.reservationId(), request.referralId(), heldUntil);
    }
}
