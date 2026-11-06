package healoworld.institution_service.presentation.dto.response;

import healoworld.institution_service.domain.entity.Equipment;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.UUID;

// 내부 API 응답: 장비의 실제 소속 기관
@Getter
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class EquipmentOwnerResponse {

    private final UUID equipmentId;
    private final UUID institutionId;

    public static EquipmentOwnerResponse from(Equipment equipment) {
        return new EquipmentOwnerResponse(equipment.getId(), equipment.getInstitutionId());
    }
}
