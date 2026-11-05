package healoworld.slot_service.application.service;

import healoworld.slot_service.application.dto.SlotTimeRange;
import healoworld.slot_service.global.exception.BaseException;
import healoworld.slot_service.global.exception.ErrorCode;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

@Component
public class SlotGenerationPlanner {

    public List<SlotTimeRange> plan(
            LocalTime operatingStartTime,
            LocalTime operatingEndTime,
            Integer durationMinutes
    ) {
        if (operatingStartTime == null || operatingEndTime == null
                || durationMinutes == null || durationMinutes < 1) {
            throw new BaseException(ErrorCode.INVALID_INPUT);
        }

        if (!operatingStartTime.isBefore(operatingEndTime)) {
            throw new BaseException(ErrorCode.SLOT_INVALID_TIME_RANGE);
        }

        Duration examDuration = Duration.ofMinutes(durationMinutes);
        List<SlotTimeRange> ranges = new ArrayList<>();
        LocalTime cursor = operatingStartTime;

        while (Duration.between(cursor, operatingEndTime).compareTo(examDuration) >= 0) {
            LocalTime next = cursor.plusMinutes(durationMinutes);
            ranges.add(new SlotTimeRange(cursor, next));
            cursor = next;
        }

        return List.copyOf(ranges);
    }
}