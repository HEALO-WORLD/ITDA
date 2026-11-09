package healoworld.referral_service.application.event;

import java.time.LocalDateTime;
import java.util.UUID;

public record EquipmentDowntimeStartedEvent(
        UUID eventId,
        String eventType,
        LocalDateTime occurredAt,
        UUID equipmentId,
        UUID institutionId,
        String reason,
        LocalDateTime startAt,
        LocalDateTime endAt
) {
    public static final String TYPE = "EquipmentDowntimeStarted";
}