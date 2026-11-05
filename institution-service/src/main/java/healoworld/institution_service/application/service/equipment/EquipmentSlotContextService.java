package healoworld.institution_service.application.service.equipment;

import healoworld.institution_service.domain.entity.Equipment;
import healoworld.institution_service.domain.entity.EquipmentExamType;
import healoworld.institution_service.domain.entity.EquipmentOperatingHours;
import healoworld.institution_service.domain.entity.ExamType;
import healoworld.institution_service.domain.entity.Institution;
import healoworld.institution_service.domain.enums.EquipmentStatus;
import healoworld.institution_service.domain.enums.InstitutionType;
import healoworld.institution_service.global.exception.BaseException;
import healoworld.institution_service.global.exception.ErrorCode;
import healoworld.institution_service.infrastructure.repository.EquipmentExamTypeRepository;
import healoworld.institution_service.infrastructure.repository.EquipmentOperatingHoursRepository;
import healoworld.institution_service.infrastructure.repository.EquipmentRepository;
import healoworld.institution_service.infrastructure.repository.ExamTypeRepository;
import healoworld.institution_service.infrastructure.repository.InstitutionRepository;
import healoworld.institution_service.presentation.dto.response.EquipmentSlotContextResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class EquipmentSlotContextService {

    private final EquipmentRepository equipmentRepository;
    private final InstitutionRepository institutionRepository;
    private final ExamTypeRepository examTypeRepository;
    private final EquipmentExamTypeRepository equipmentExamTypeRepository;
    private final EquipmentOperatingHoursRepository operatingHoursRepository;

    @Transactional(readOnly = true)
    public EquipmentSlotContextResponse getSlotContext(
            UUID equipmentId,
            UUID examTypeId,
            LocalDate date
    ) {
        if (equipmentId == null || examTypeId == null || date == null) {
            throw new BaseException(ErrorCode.INVALID_INPUT);
        }

        Equipment equipment = equipmentRepository.findByIdAndDeletedAtIsNull(equipmentId)
                .orElseThrow(() -> new BaseException(ErrorCode.RESOURCE_NOT_FOUND));

        Institution institution = institutionRepository.findByIdAndDeletedAtIsNull(equipment.getInstitutionId())
                .orElseThrow(() -> new BaseException(ErrorCode.RESOURCE_NOT_FOUND));

        if (!institution.isActive()
                || institution.getType() != InstitutionType.EXAM
                || equipment.getStatus() != EquipmentStatus.OPERATIONAL) {
            throw new BaseException(ErrorCode.EQUIPMENT_NOT_AVAILABLE);
        }

        ExamType examType = examTypeRepository.findByIdAndDeletedAtIsNull(examTypeId)
                .orElseThrow(() -> new BaseException(ErrorCode.RESOURCE_NOT_FOUND));

        if (equipment.getModality() != examType.getModality()) {
            throw new BaseException(ErrorCode.INVALID_INPUT);
        }

        EquipmentExamType supportedExamType = equipmentExamTypeRepository.findByEquipmentIdAndExamTypeIdAndDeletedAtIsNull(equipmentId, examTypeId)
                .orElseThrow(() -> new BaseException(ErrorCode.RESOURCE_NOT_FOUND));

        if (supportedExamType.getDurationMinutes() == null || supportedExamType.getDurationMinutes() < 1) {
            throw new BaseException(ErrorCode.EQUIPMENT_NOT_AVAILABLE);
        }

        EquipmentOperatingHours hours = operatingHoursRepository.findByEquipmentIdAndWeekAndDeletedAtIsNull(equipmentId, date.getDayOfWeek().getValue())
                .orElseThrow(() -> new BaseException(ErrorCode.OPERATING_HOURS_NOT_AVAILABLE));

        return EquipmentSlotContextResponse.from(
                equipment,
                supportedExamType,
                date,
                hours
        );
    }
}