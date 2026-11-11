package healoworld.slot_service.application.event.downtime;

import java.time.LocalDateTime;
import java.util.UUID;

public record EquipmentAvailabilityChangedEvent(
        UUID eventId,
        String eventType,
        LocalDateTime occurredAt,
        UUID equipmentId,
        UUID institutionId,
        long revision,
        String status,
        UUID changedBy,
        String reason
) {
    public static final String TYPE = "EquipmentAvailabilityChanged";
    public static final String TOPIC = "equipment-availability";
}