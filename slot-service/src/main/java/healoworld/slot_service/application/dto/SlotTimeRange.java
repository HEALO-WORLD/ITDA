package healoworld.slot_service.application.dto;

import java.time.LocalTime;

public record SlotTimeRange(
        LocalTime startTime,
        LocalTime endTime
) {
}