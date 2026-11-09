package healoworld.institution_service.application.service.equipment;

import healoworld.institution_service.domain.entity.Institution;
import healoworld.institution_service.domain.enums.EquipmentStatus;
import healoworld.institution_service.domain.enums.InstitutionType;
import healoworld.institution_service.domain.enums.PartnershipStatus;
import healoworld.institution_service.domain.enums.Role;
import healoworld.institution_service.global.exception.BaseException;
import healoworld.institution_service.global.exception.ErrorCode;
import healoworld.institution_service.infrastructure.repository.EquipmentRepository;
import healoworld.institution_service.infrastructure.repository.InstitutionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class EquipmentVisibilityService {

    private final EquipmentRepository equipmentRepository;
    private final InstitutionRepository institutionRepository;

    @Transactional(readOnly = true)
    public Set<UUID> getVisibleEquipmentIds(
            UUID callerInstitutionId,
            Role role,
            UUID institutionId,
            boolean onlyOperational
    ) {
        if (callerInstitutionId == null || role == null) {
            throw new BaseException(ErrorCode.INVALID_INPUT);
        }

        if (role == Role.REQUESTER && !onlyOperational) {
            throw new BaseException(ErrorCode.FORBIDDEN);
        }

        if (role != Role.ADMIN) {
            Institution caller = institutionRepository.findByIdAndDeletedAtIsNull(callerInstitutionId)
                    .orElseThrow(() -> new BaseException(ErrorCode.RESOURCE_NOT_FOUND));

            InstitutionType expectedType = role == Role.EXAMINER ? InstitutionType.EXAM : InstitutionType.REQUESTER;

            if (!caller.isActive() || caller.getType() != expectedType) {
                throw new BaseException(ErrorCode.FORBIDDEN);
            }
        }

        return Set.copyOf(equipmentRepository.findVisibleEquipmentIds(
                callerInstitutionId,
                role == Role.ADMIN,
                role == Role.REQUESTER,
                institutionId,
                onlyOperational ? EquipmentStatus.OPERATIONAL : null,
                InstitutionType.EXAM,
                PartnershipStatus.ACTIVE
        ));
    }
}