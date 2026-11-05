package healoworld.institution_service.presentation.dto.response;

import healoworld.institution_service.domain.entity.EquipmentOperatingHours;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.LocalTime;
import java.util.UUID;

@Getter
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class EquipmentOperatingHoursResponse {

    private final UUID id;
    private final UUID equipmentId;
    private final Integer week;
    private final LocalTime startTime;
    private final LocalTime endTime;

    public static EquipmentOperatingHoursResponse from(EquipmentOperatingHours hours) {
        return new EquipmentOperatingHoursResponse(
                hours.getId(),
                hours.getEquipmentId(),
                hours.getWeek(),
                hours.getStartTime(),
                hours.getEndTime()
        );
    }
}