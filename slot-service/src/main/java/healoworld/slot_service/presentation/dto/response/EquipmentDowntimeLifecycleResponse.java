package healoworld.slot_service.presentation.dto.response;

import healoworld.slot_service.domain.entity.EquipmentDowntime;
import healoworld.slot_service.domain.enums.EquipmentDowntimeType;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.UUID;

public record EquipmentDowntimeLifecycleResponse(UUID id, UUID equipmentId,
                                                 EquipmentDowntimeType downtimeType, OffsetDateTime startedAt, OffsetDateTime endedAt,
                                                 OffsetDateTime recoveredAt, String recoveryReason) {
    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    public static EquipmentDowntimeLifecycleResponse from(EquipmentDowntime d) {
        return new EquipmentDowntimeLifecycleResponse(d.getId(), d.getEquipmentId(), d.getDowntimeType(),
                d.getStartedAt().atZone(ZONE).toOffsetDateTime(), d.getEndedAt().atZone(ZONE).toOffsetDateTime(),
                d.getRecoveredAt() == null ? null : d.getRecoveredAt().atZone(ZONE).toOffsetDateTime(),
                d.getRecoveryReason());
    }
}