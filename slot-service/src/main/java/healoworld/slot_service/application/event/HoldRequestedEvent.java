package healoworld.slot_service.application.event;

import java.time.LocalDateTime;
import java.util.UUID;

// slot-commands로 수신하는 HOLD 요청 (Referral → Slot, key = slotId)
public record HoldRequestedEvent(
        UUID eventId,
        String eventType,
        LocalDateTime occurredAt,
        UUID slotId,
        UUID reservationId,
        UUID referralId
) {
    public static final String TYPE = "HoldRequested";
}
