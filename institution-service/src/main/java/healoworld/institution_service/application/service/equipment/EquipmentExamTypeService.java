package healoworld.institution_service.application.service.equipment;

import healoworld.institution_service.domain.entity.Equipment;
import healoworld.institution_service.domain.entity.EquipmentExamType;
import healoworld.institution_service.domain.entity.ExamType;
import healoworld.institution_service.domain.entity.Institution;
import healoworld.institution_service.domain.enums.InstitutionType;
import healoworld.institution_service.domain.enums.Role;
import healoworld.institution_service.global.dto.PagedResponse;
import healoworld.institution_service.global.exception.BaseException;
import healoworld.institution_service.global.exception.ErrorCode;
import healoworld.institution_service.infrastructure.repository.EquipmentExamTypeRepository;
import healoworld.institution_service.infrastructure.repository.EquipmentRepository;
import healoworld.institution_service.infrastructure.repository.ExamTypeRepository;
import healoworld.institution_service.infrastructure.repository.InstitutionRepository;
import healoworld.institution_service.presentation.dto.request.EquipmentExamTypeCreateRequest;
import healoworld.institution_service.presentation.dto.request.EquipmentExamTypeUpdateRequest;
import healoworld.institution_service.presentation.dto.response.EquipmentExamTypeResponse;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class EquipmentExamTypeService {

    private static final String UNIQUE_EQUIPMENT_EXAM_TYPE_CONSTRAINT = "uk_equipment_exam_type";

    private final EquipmentRepository equipmentRepository;
    private final ExamTypeRepository examTypeRepository;
    private final EquipmentExamTypeRepository equipmentExamTypeRepository;
    private final InstitutionRepository institutionRepository;
    private final EquipmentService equipmentService;

    @Transactional
    public EquipmentExamTypeResponse createEquipmentExamType(
            UUID equipmentId,
            EquipmentExamTypeCreateRequest request,
            UUID userId,
            UUID userInstitutionId,
            Role userRole
    ) {
        if (equipmentId == null
                || request == null
                || request.getExamTypeId() == null
                || request.getDurationMinutes() == null
                || request.getDurationMinutes() < 1
                || userId == null
                || userInstitutionId == null
                || userRole == null) {
            throw new BaseException(ErrorCode.INVALID_INPUT);
        }

        if (userRole != Role.EXAMINER) {
            throw new BaseException(ErrorCode.FORBIDDEN);
        }

        Equipment equipment = findOwnedEquipmentForUpdate(equipmentId, userInstitutionId);

        ExamType examType = examTypeRepository.findByIdAndDeletedAtIsNull(request.getExamTypeId())
                        .orElseThrow(() -> new BaseException(ErrorCode.RESOURCE_NOT_FOUND));

        if (equipment.getModality() != examType.getModality()) {
            throw new BaseException(ErrorCode.INVALID_INPUT);
        }

        if (equipmentExamTypeRepository.findByEquipmentIdAndExamTypeId(equipmentId, examType.getId()).isPresent()) {
            throw new BaseException(ErrorCode.DUPLICATE_EQUIPMENT_EXAM_TYPE);
        }

        EquipmentExamType equipmentExamType = EquipmentExamType.create(
                equipment, examType, request.getDurationMinutes()
        );

        EquipmentExamType saved;
        try {
            saved = equipmentExamTypeRepository.saveAndFlush(equipmentExamType);
        } catch (DataIntegrityViolationException exception) {
            throw translateConstraint(exception);
        }

        return EquipmentExamTypeResponse.from(saved, examType);
    }

    @Transactional
    public EquipmentExamTypeResponse updateEquipmentExamType(
            UUID equipmentId,
            UUID examTypeId,
            EquipmentExamTypeUpdateRequest request,
            UUID userId,
            UUID userInstitutionId,
            Role userRole
    ) {
        if (equipmentId == null || examTypeId == null || request == null
                || request.getDurationMinutes() == null || request.getDurationMinutes() < 1
                || userId == null || userInstitutionId == null || userRole == null) {
            throw new BaseException(ErrorCode.INVALID_INPUT);
        }

        if (userRole != Role.EXAMINER) {
            throw new BaseException(ErrorCode.FORBIDDEN);
        }

        findOwnedEquipmentForUpdate(equipmentId, userInstitutionId);

        ExamType examType = examTypeRepository.findByIdAndDeletedAtIsNull(examTypeId)
                .orElseThrow(() -> new BaseException(ErrorCode.RESOURCE_NOT_FOUND));

        EquipmentExamType equipmentExamType = equipmentExamTypeRepository
                .findByEquipmentIdAndExamTypeIdAndDeletedAtIsNull(equipmentId, examTypeId)
                .orElseThrow(() -> new BaseException(ErrorCode.RESOURCE_NOT_FOUND));

        equipmentExamType.changeDurationMinutes(request.getDurationMinutes());
        return EquipmentExamTypeResponse.from(equipmentExamType, examType);
    }

    @Transactional(readOnly = true)
    public PagedResponse<EquipmentExamTypeResponse> getEquipmentExamTypes(
            UUID equipmentId,
            UUID userId,
            UUID userInstitutionId,
            Role userRole,
            Integer page,
            Integer size
    ) {
        if (page == null || size == null
                || page < 0 || size < 1 || size > 100) {
            throw new BaseException(ErrorCode.INVALID_INPUT);
        }

        equipmentService.getEquipment(
                equipmentId,
                userId,
                userInstitutionId,
                userRole
        );

        Page<EquipmentExamTypeResponse> responses = equipmentExamTypeRepository.findSupportedExamTypes(
                equipmentId,
                PageRequest.of(page, size)
        );

        return PagedResponse.from(responses);
    }

    private RuntimeException translateConstraint(DataIntegrityViolationException exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation
                    && "23505".equals(violation.getSQLState())
                    && UNIQUE_EQUIPMENT_EXAM_TYPE_CONSTRAINT.equals(violation.getConstraintName())) {
                return new BaseException(ErrorCode.DUPLICATE_EQUIPMENT_EXAM_TYPE);
            }
        }
        return exception;
    }

    private Equipment findOwnedEquipmentForUpdate(UUID equipmentId, UUID userInstitutionId) {
        Equipment equipment = equipmentRepository.findForUpdateById(equipmentId)
                .orElseThrow(() -> new BaseException(ErrorCode.RESOURCE_NOT_FOUND));

        if (!equipment.getInstitutionId().equals(userInstitutionId)) {
            throw new BaseException(ErrorCode.FORBIDDEN);
        }

        Institution institution = institutionRepository.findByIdAndDeletedAtIsNull(userInstitutionId)
                .orElseThrow(() -> new BaseException(ErrorCode.RESOURCE_NOT_FOUND));

        if (!institution.isActive() || institution.getType() != InstitutionType.EXAM) {
            throw new BaseException(ErrorCode.FORBIDDEN);
        }

        return equipment;
    }
}
