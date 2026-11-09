package healoworld.institution_service.presentation.dto.response;

import healoworld.institution_service.domain.entity.Equipment;
import healoworld.institution_service.domain.entity.EquipmentExamType;
import healoworld.institution_service.domain.entity.EquipmentOperatingHours;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

@Getter
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class EquipmentSlotContextResponse {

    private final UUID equipmentId;
    private final UUID institutionId;
    private final UUID examTypeId;
    private final Integer durationMinutes;
    private final LocalDate date;
    private final LocalTime operatingStartTime;
    private final LocalTime operatingEndTime;

    public static EquipmentSlotContextResponse from(
            Equipment equipment,
            EquipmentExamType supportedExamType,
            LocalDate date,
            EquipmentOperatingHours hours
    ) {
        return new EquipmentSlotContextResponse(
                equipment.getId(),
                equipment.getInstitutionId(),
                supportedExamType.getExamTypeId(),
                supportedExamType.getDurationMinutes(),
                date,
                hours.getStartTime(),
                hours.getEndTime()
        );
    }
}