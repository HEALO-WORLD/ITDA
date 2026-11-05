package healoworld.slot_service.infrastructure.client.dto;

import java.util.UUID;

// Institution Service 내부 API(GET /api/v1/internal/equipments/{equipmentId}/owner) 응답
public record EquipmentOwnerResponse(
        UUID equipmentId,
        UUID institutionId
) {
}
