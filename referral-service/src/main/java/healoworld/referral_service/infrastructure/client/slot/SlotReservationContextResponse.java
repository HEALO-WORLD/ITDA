package healoworld.referral_service.infrastructure.client.slot;

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
}
