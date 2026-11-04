package healoworld.institution_service.presentation.dto.response;

import healoworld.institution_service.domain.entity.EquipmentExamType;
import healoworld.institution_service.domain.entity.ExamType;
import healoworld.institution_service.domain.enums.Modality;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.UUID;

@Getter
@AllArgsConstructor
public class EquipmentExamTypeResponse {

    private final UUID id;
    private final UUID equipmentId;
    private final UUID examTypeId;
    private final String examTypeName;
    private final Modality modality;
    private final Integer durationMinutes;

    public static EquipmentExamTypeResponse from(EquipmentExamType equipmentExamType, ExamType examType) {
        return new EquipmentExamTypeResponse(
                equipmentExamType.getId(),
                equipmentExamType.getEquipmentId(),
                equipmentExamType.getExamTypeId(),
                examType.getName(),
                examType.getModality(),
                examType.getDurationMinutes()
        );
    }
}