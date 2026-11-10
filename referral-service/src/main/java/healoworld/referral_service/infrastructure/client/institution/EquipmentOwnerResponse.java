package healoworld.referral_service.infrastructure.client.institution;

import java.util.UUID;

public record EquipmentOwnerResponse(
        UUID equipmentId,
        UUID institutionId
) {
}
