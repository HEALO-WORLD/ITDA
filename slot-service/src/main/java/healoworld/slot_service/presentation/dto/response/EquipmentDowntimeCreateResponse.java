package healoworld.slot_service.presentation.dto.response;

import healoworld.slot_service.application.dto.EquipmentDowntimeRegistration;
import healoworld.slot_service.domain.entity.EquipmentDowntime;
import healoworld.slot_service.domain.enums.EquipmentDowntimeType;
import healoworld.slot_service.domain.enums.SlotStatus;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

@Getter
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class EquipmentDowntimeCreateResponse {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Seoul");

    private final UUID id;
    private final UUID equipmentId;
    private final OffsetDateTime startedAt;
    private final OffsetDateTime endedAt;
    private final String reason;
    private final EquipmentDowntimeType downtimeType;
    // 이번 등록으로 AVAILABLE → UNAVAILABLE이 된 슬롯 수
    private final int affectedSlotCount;
    // 예약이 잡혀 있어 상태를 유지한 HELD·BOOKED 슬롯. 예약·의뢰 후속 처리는 Referral Service가 담당한다
    private final List<ConflictedSlot> conflictedSlots;

    public static EquipmentDowntimeCreateResponse from(EquipmentDowntimeRegistration registration) {
        EquipmentDowntime downtime = registration.downtime();
        return new EquipmentDowntimeCreateResponse(
                downtime.getId(),
                downtime.getEquipmentId(),
                downtime.getStartedAt().atZone(BUSINESS_ZONE).toOffsetDateTime(),
                downtime.getEndedAt().atZone(BUSINESS_ZONE).toOffsetDateTime(),
                downtime.getReason(),
                downtime.getDowntimeType(),
                registration.blockedSlotCount(),
                registration.conflictedSlots().stream()
                        .map(slot -> new ConflictedSlot(slot.slotId(), slot.status(), slot.reservationId()))
                        .toList()
        );
    }

    public record ConflictedSlot(UUID slotId, SlotStatus status, UUID reservationId) {
    }
}
