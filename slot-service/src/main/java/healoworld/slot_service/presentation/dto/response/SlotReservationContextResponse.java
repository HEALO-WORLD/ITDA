package healoworld.slot_service.presentation.dto.response;

import healoworld.slot_service.domain.entity.Slot;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

public record SlotReservationContextResponse(
        UUID slotId,
        UUID equipmentId,
        UUID examTypeId,
        LocalDate date,
        LocalTime startTime,
        LocalTime endTime
) {
    public static SlotReservationContextResponse from(Slot slot) {
        return new SlotReservationContextResponse(
                slot.getId(),
                slot.getEquipmentId(),
                slot.getExamTypeId(),
                slot.getDate(),
                slot.getStartTime(),
                slot.getEndTime()
        );
    }
}
