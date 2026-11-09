package healoworld.referral_service.application.event.result;

import java.time.LocalDateTime;
import java.util.UUID;

public record SlotResultMessage(
        UUID eventId,
        String eventType,
        LocalDateTime occurredAt,
        UUID slotId,
        UUID reservationId
) {
}
