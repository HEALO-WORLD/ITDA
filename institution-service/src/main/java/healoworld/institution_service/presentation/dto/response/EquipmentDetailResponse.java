package healoworld.institution_service.presentation.dto.response;

import healoworld.institution_service.domain.entity.Equipment;
import healoworld.institution_service.domain.enums.EquipmentStatus;
import healoworld.institution_service.domain.enums.Modality;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.UUID;

@Getter
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class EquipmentDetailResponse {

    private final UUID id;
    private final UUID institutionId;
    private final String name;
    private final Modality modality;
    private final EquipmentStatus status;

    public static EquipmentDetailResponse from(Equipment equipment) {
        return new EquipmentDetailResponse(
                equipment.getId(),
                equipment.getInstitutionId(),
                equipment.getName(),
                equipment.getModality(),
                equipment.getStatus()
        );
    }
}