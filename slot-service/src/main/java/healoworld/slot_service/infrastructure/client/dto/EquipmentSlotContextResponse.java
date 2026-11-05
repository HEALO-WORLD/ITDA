package healoworld.slot_service.infrastructure.client.dto;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

public record EquipmentSlotContextResponse(
        UUID equipmentId,
        UUID institutionId,
        UUID examTypeId,
        Integer durationMinutes,
        LocalDate date,
        LocalTime operatingStartTime,
        LocalTime operatingEndTime
) {
}