package healoworld.institution_service.application.service.institution;

import healoworld.institution_service.domain.entity.Institution;
import healoworld.institution_service.domain.enums.InstitutionType;
import healoworld.institution_service.domain.enums.PartnershipStatus;
import healoworld.institution_service.global.exception.BaseException;
import healoworld.institution_service.global.exception.ErrorCode;
import healoworld.institution_service.infrastructure.repository.InstitutionRepository;
import healoworld.institution_service.infrastructure.repository.PartnershipRepository;
import healoworld.institution_service.infrastructure.repository.UserRepository;
import healoworld.institution_service.presentation.dto.request.InstitutionCreateRequest;
import healoworld.institution_service.presentation.dto.request.InstitutionUpdateRequest;
import healoworld.institution_service.presentation.dto.response.InstitutionResponse;
import healoworld.institution_service.presentation.dto.response.PublicInstitutionResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class InstitutionService {
    private final InstitutionRepository institutionRepository;
    private final PartnershipRepository partnershipRepository;
    private final UserRepository userRepository;

    public List<PublicInstitutionResponse> getPublicInstitutions() {
        return institutionRepository.findAllByActiveTrueAndDeletedAtIsNullAndTypeNot(InstitutionType.PLATFORM)
                .stream().map(PublicInstitutionResponse::from)
                .toList();
    }

    /**
     * ==== 관리자 ====
     */
    @Transactional
    public InstitutionResponse create(InstitutionCreateRequest request) {
        // 유스케이스 규칙: 관리자 API로 운영사 기관 등록 불가
        if (!request.type().isMedical()) {
            throw new BaseException(ErrorCode.INSTITUTION_TYPE_NOT_ALLOWED);
        }

        validateCareInstitutionCodeNotDuplicated(request.careInstitutionCode());

        Institution institution = Institution.create(
                request.name(), request.type(), request.address(), request.careInstitutionCode());

        try {
            institutionRepository.saveAndFlush(institution);
        } catch (DataIntegrityViolationException e) {
            validateCareInstitutionCodeNotDuplicated(request.careInstitutionCode());
            throw e;
        }

        return InstitutionResponse.from(institution);
    }

    public List<InstitutionResponse> getInstitutions() {
        return institutionRepository.findAllByDeletedAtIsNullOrderByCreatedAtDesc()
                .stream().map(InstitutionResponse::from)
                .toList();
    }

    @Transactional
    public InstitutionResponse update(UUID institutionId, InstitutionUpdateRequest request) {
        Institution institution = getInstitution(institutionId);
        institution.update(request.name(), request.address());
        return InstitutionResponse.from(institution);
    }

    @Transactional
    public void delete(UUID institutionId, UUID adminId) {
        Institution institution = getInstitution(institutionId);

        // 다른 애그리거트 확인: 진행 중 협약 / 소속 사용자
        if (partnershipRepository.existsByInstitutionIdAndStatusIn(institutionId, PartnershipStatus.inProgress())) {
            throw new BaseException(ErrorCode.INSTITUTION_HAS_ACTIVE_PARTNERSHIP);
        }
        if (userRepository.existsByInstitutionIdAndDeletedAtIsNull(institutionId)) {
            throw new BaseException(ErrorCode.INSTITUTION_HAS_USERS);
        }
        institution.delete(adminId);
    }

    private void validateCareInstitutionCodeNotDuplicated(String careInstitutionCode) {
        if (institutionRepository.existsByCareInstitutionCode(careInstitutionCode)) {
            throw new BaseException(ErrorCode.DUPLICATE_CARE_INSTITUTION_CODE);
        }
    }

    private Institution getInstitution(UUID institutionId) {
        return institutionRepository.findByIdAndDeletedAtIsNull(institutionId)
                .orElseThrow(() -> new BaseException(ErrorCode.INSTITUTION_NOT_FOUND));
    }
}
