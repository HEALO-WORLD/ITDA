package healoworld.slot_service.presentation.dto.response;

import healoworld.slot_service.domain.entity.Slot;
import healoworld.slot_service.domain.enums.SlotStatus;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.UUID;

@Getter
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class SlotCreateResponse {

    private final UUID id;
    private final UUID equipmentId;
    private final UUID examTypeId;
    private final OffsetDateTime startsAt;
    private final OffsetDateTime endsAt;
    private final SlotStatus status;

    public static SlotCreateResponse from(Slot slot) {
        ZoneId zoneId = ZoneId.of("Asia/Seoul");

        OffsetDateTime startsAt = slot.getDate()
                .atTime(slot.getStartTime())
                .atZone(zoneId)
                .toOffsetDateTime();

        OffsetDateTime endsAt = slot.getDate()
                .atTime(slot.getEndTime())
                .atZone(zoneId)
                .toOffsetDateTime();

        return new SlotCreateResponse(
                slot.getId(),
                slot.getEquipmentId(),
                slot.getExamTypeId(),
                startsAt,
                endsAt,
                slot.getStatus()
        );
    }
}