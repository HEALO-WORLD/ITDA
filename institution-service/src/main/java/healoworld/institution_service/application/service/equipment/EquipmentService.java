package healoworld.institution_service.application.service.equipment;

import healoworld.institution_service.domain.entity.Equipment;
import healoworld.institution_service.domain.entity.Institution;
import healoworld.institution_service.domain.enums.InstitutionType;
import healoworld.institution_service.domain.enums.Modality;
import healoworld.institution_service.domain.enums.Role;
import healoworld.institution_service.global.dto.PagedResponse;
import healoworld.institution_service.global.exception.BaseException;
import healoworld.institution_service.global.exception.ErrorCode;
import healoworld.institution_service.infrastructure.repository.EquipmentRepository;
import healoworld.institution_service.infrastructure.repository.InstitutionRepository;
import healoworld.institution_service.presentation.dto.request.EquipmentCreateRequest;
import healoworld.institution_service.presentation.dto.request.EquipmentUpdateRequest;
import healoworld.institution_service.presentation.dto.response.EquipmentCreateResponse;
import healoworld.institution_service.presentation.dto.response.EquipmentDetailResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class EquipmentService {

    private final EquipmentRepository equipmentRepository;
    private final InstitutionRepository institutionRepository;

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

        Equipment savedEquipment = equipmentRepository.save(equipment);

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

        if (!equipment.getInstitutionId().equals(userInstitutionId)) {
            throw new BaseException(ErrorCode.FORBIDDEN);
        }

        Institution institution = institutionRepository.findByIdAndDeletedAtIsNull(equipment.getInstitutionId())
                .orElseThrow(() -> new BaseException(ErrorCode.RESOURCE_NOT_FOUND));

        if (!institution.isActive()
                || institution.getType() != InstitutionType.EXAM) {
            throw new BaseException(ErrorCode.FORBIDDEN);
        }

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

        if ((userRole != Role.REQUESTER && userRole != Role.EXAMINER)
                || !institutionId.equals(userInstitutionId)) {
            throw new BaseException(ErrorCode.FORBIDDEN);
        }

        if (page == null || size == null
                || page < 0 || size < 1 || size > 100) {
            throw new BaseException(ErrorCode.INVALID_INPUT);
        }

        Institution institution = institutionRepository.findByIdAndDeletedAtIsNull(institutionId)
                .orElseThrow(() -> new BaseException(ErrorCode.RESOURCE_NOT_FOUND));

        if (!institution.isActive()
                || institution.getType() != InstitutionType.EXAM) {
            throw new BaseException(ErrorCode.FORBIDDEN);
        }

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

        Equipment equipment = equipmentRepository.findByIdAndDeletedAtIsNull(equipmentId)
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

        equipment.changeName(request.getName());

        return EquipmentDetailResponse.from(equipment);
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
}