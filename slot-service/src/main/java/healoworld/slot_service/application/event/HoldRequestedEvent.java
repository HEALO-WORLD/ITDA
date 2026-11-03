package healoworld.slot_service.application.event;

import java.time.LocalDateTime;
import java.util.UUID;

// slot-commands로 수신하는 HOLD 요청 (Referral → Slot, key = slotId)
// Referral이 referralId를 함께 보내도 Slot은 쓰지 않으므로 받지 않는다(모르는 필드는 무시된다)
public record HoldRequestedEvent(
        UUID eventId,
        String eventType,
        LocalDateTime occurredAt,
        UUID slotId,
        UUID reservationId
) {
    public static final String TYPE = "HoldRequested";
}
