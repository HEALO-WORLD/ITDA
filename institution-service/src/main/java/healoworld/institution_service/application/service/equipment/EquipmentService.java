package healoworld.institution_service.application.service.equipment;

import healoworld.institution_service.domain.entity.Equipment;
import healoworld.institution_service.domain.entity.Institution;
import healoworld.institution_service.domain.enums.InstitutionType;
import healoworld.institution_service.domain.enums.Modality;
import healoworld.institution_service.domain.enums.PartnershipStatus;
import healoworld.institution_service.domain.enums.Role;
import healoworld.institution_service.global.dto.PagedResponse;
import healoworld.institution_service.global.exception.BaseException;
import healoworld.institution_service.global.exception.ErrorCode;
import healoworld.institution_service.infrastructure.repository.EquipmentRepository;
import healoworld.institution_service.infrastructure.repository.InstitutionRepository;
import healoworld.institution_service.infrastructure.repository.PartnershipRepository;
import healoworld.institution_service.presentation.dto.request.EquipmentCreateRequest;
import healoworld.institution_service.presentation.dto.request.EquipmentUpdateRequest;
import healoworld.institution_service.presentation.dto.response.EquipmentCreateResponse;
import healoworld.institution_service.presentation.dto.response.EquipmentDetailResponse;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class EquipmentService {

    private static final String UNIQUE_EQUIPMENT_NAME_INDEX = "uq_equipment_active_institution_name";

    private final EquipmentRepository equipmentRepository;
    private final InstitutionRepository institutionRepository;
    private final PartnershipRepository partnershipRepository;

    @Transactional
    public EquipmentCreateResponse createEquipment(
            UUID institutionId,
            EquipmentCreateRequest request,
            UUID userId,
            UUID userInstitutionId,
            Role userRole
    ) {
        if (institutionId == null || userId == null
                || userInstitutionId == null || userRole == null) {
            throw new BaseException(ErrorCode.INVALID_INPUT);
        }

        if (userRole != Role.EXAMINER
                || !institutionId.equals(userInstitutionId)) {
            throw new BaseException(ErrorCode.FORBIDDEN);
        }

        validateRequest(request);

        Institution institution = institutionRepository.findByIdAndDeletedAtIsNull(institutionId)
                .orElseThrow(() -> new BaseException(ErrorCode.RESOURCE_NOT_FOUND));

        if (!institution.isActive()
                || institution.getType() != InstitutionType.EXAM) {
            throw new BaseException(ErrorCode.INSTITUTION_NOT_AVAILABLE);
        }

        Equipment equipment = Equipment.create(
                institutionId,
                request.getName(),
                request.getModality()
        );

        if (equipmentRepository.existsByInstitutionIdAndNameAndDeletedAtIsNull(
                institutionId, equipment.getName())) {
            throw new BaseException(ErrorCode.DUPLICATE_EQUIPMENT_NAME);
        }

        Equipment savedEquipment;
        try {
            savedEquipment = equipmentRepository.saveAndFlush(equipment);
        } catch (DataIntegrityViolationException exception) {
            throw translateConstraint(exception);
        }

        return EquipmentCreateResponse.from(savedEquipment);
    }

    @Transactional(readOnly = true)
    public EquipmentDetailResponse getEquipment(
            UUID equipmentId,
            UUID userId,
            UUID userInstitutionId,
            Role userRole
    ) {
        if (equipmentId == null || userId == null
                || userInstitutionId == null || userRole == null) {
            throw new BaseException(ErrorCode.INVALID_INPUT);
        }

        if (userRole != Role.REQUESTER && userRole != Role.EXAMINER) {
            throw new BaseException(ErrorCode.FORBIDDEN);
        }

        Equipment equipment = equipmentRepository.findByIdAndDeletedAtIsNull(equipmentId)
                .orElseThrow(() -> new BaseException(ErrorCode.RESOURCE_NOT_FOUND));

        validateReadAccess(
                userInstitutionId,
                userRole,
                equipment.getInstitutionId()
        );

        return EquipmentDetailResponse.from(equipment);
    }

    @Transactional(readOnly = true)
    public PagedResponse<EquipmentDetailResponse> getEquipments(
            UUID institutionId,
            UUID userId,
            UUID userInstitutionId,
            Role userRole,
            Modality modality,
            Integer page,
            Integer size
    ) {
        if (institutionId == null || userId == null
                || userInstitutionId == null || userRole == null) {
            throw new BaseException(ErrorCode.INVALID_INPUT);
        }

        if (userRole != Role.REQUESTER && userRole != Role.EXAMINER) {
            throw new BaseException(ErrorCode.FORBIDDEN);
        }

        if (page == null || size == null
                || page < 0 || size < 1 || size > 100) {
            throw new BaseException(ErrorCode.INVALID_INPUT);
        }

        validateReadAccess(
                userInstitutionId,
                userRole,
                institutionId
        );

        Pageable pageable = PageRequest.of(page, size);

        Page<Equipment> equipments = equipmentRepository.findEquipments(
                institutionId,
                modality,
                pageable
        );

        Page<EquipmentDetailResponse> responses = equipments.map(EquipmentDetailResponse::from);

        return PagedResponse.from(responses);
    }

    @Transactional
    public EquipmentDetailResponse updateEquipment(
            UUID equipmentId,
            EquipmentUpdateRequest request,
            UUID userId,
            UUID userInstitutionId,
            Role userRole
    ) {
        if (equipmentId == null || userId == null
                || userInstitutionId == null || userRole == null) {
            throw new BaseException(ErrorCode.INVALID_INPUT);
        }

        if (userRole != Role.EXAMINER) {
            throw new BaseException(ErrorCode.FORBIDDEN);
        }

        validateUpdateRequest(request);

        Equipment equipment = equipmentRepository.findForUpdateById(equipmentId)
                .orElseThrow(() -> new BaseException(ErrorCode.RESOURCE_NOT_FOUND));

        if (!equipment.getInstitutionId().equals(userInstitutionId)) {
            throw new BaseException(ErrorCode.FORBIDDEN);
        }

        Institution institution = institutionRepository.findByIdAndDeletedAtIsNull(equipment.getInstitutionId())
                .orElseThrow(() -> new BaseException(ErrorCode.RESOURCE_NOT_FOUND));

        if (!institution.isActive()
                || institution.getType() != InstitutionType.EXAM) {
            throw new BaseException(ErrorCode.FORBIDDEN);
        }

        String normalizedName = request.getName().trim();
        if (equipmentRepository.existsByInstitutionIdAndNameAndDeletedAtIsNullAndIdNot(
                equipment.getInstitutionId(), normalizedName, equipmentId)) {
            throw new BaseException(ErrorCode.DUPLICATE_EQUIPMENT_NAME);
        }

        equipment.changeName(normalizedName);
        try {
            equipmentRepository.flush();
        } catch (DataIntegrityViolationException exception) {
            throw translateConstraint(exception);
        }

        return EquipmentDetailResponse.from(equipment);
    }

    private RuntimeException translateConstraint(DataIntegrityViolationException exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation
                    && "23505".equals(violation.getSQLState())
                    && UNIQUE_EQUIPMENT_NAME_INDEX.equals(violation.getConstraintName())) {
                return new BaseException(ErrorCode.DUPLICATE_EQUIPMENT_NAME);
            }
        }
        return exception;
    }

    private void validateRequest(EquipmentCreateRequest request) {
        if (request == null
                || request.getName() == null
                || request.getName().isBlank()
                || request.getName().length() > 50
                || request.getModality() == null) {
            throw new BaseException(ErrorCode.INVALID_INPUT);
        }
    }

    private void validateUpdateRequest(EquipmentUpdateRequest request) {
        if (request == null
                || request.getName() == null
                || request.getName().isBlank()
                || request.getName().length() > 50) {
            throw new BaseException(ErrorCode.INVALID_INPUT);
        }
    }

    private void validateReadAccess(
            UUID userInstitutionId,
            Role userRole,
            UUID examInstitutionId
    ) {
        Institution callerInstitution = institutionRepository.findByIdAndDeletedAtIsNull(userInstitutionId)
                .orElseThrow(() -> new BaseException(ErrorCode.RESOURCE_NOT_FOUND));

        Institution examInstitution = institutionRepository.findByIdAndDeletedAtIsNull(examInstitutionId)
                .orElseThrow(() -> new BaseException(ErrorCode.RESOURCE_NOT_FOUND));

        if (!callerInstitution.isActive()
                || !examInstitution.isActive()
                || examInstitution.getType() != InstitutionType.EXAM) {
            throw new BaseException(ErrorCode.FORBIDDEN);
        }

        if (userRole == Role.EXAMINER
                && callerInstitution.getType() == InstitutionType.EXAM
                && userInstitutionId.equals(examInstitutionId)) {
            return;
        }

        if (userRole == Role.REQUESTER
                && callerInstitution.getType() == InstitutionType.REQUESTER
                && partnershipRepository.existsByRequesterInstitutionIdAndExamInstitutionIdAndStatusAndDeletedAtIsNull(
                        userInstitutionId,
                        examInstitutionId,
                        PartnershipStatus.ACTIVE
        )) {
            return;
        }

        throw new BaseException(ErrorCode.FORBIDDEN);
    }
}
