package healoworld.institution_service.application.service.partnership;

import healoworld.institution_service.domain.entity.Institution;
import healoworld.institution_service.domain.entity.Partnership;
import healoworld.institution_service.domain.entity.PartnershipHistory;
import healoworld.institution_service.domain.enums.PartnershipStatus;
import healoworld.institution_service.domain.enums.Role;
import healoworld.institution_service.global.exception.BaseException;
import healoworld.institution_service.global.exception.ErrorCode;
import healoworld.institution_service.infrastructure.repository.InstitutionRepository;
import healoworld.institution_service.infrastructure.repository.PartnershipHistoryRepository;
import healoworld.institution_service.infrastructure.repository.PartnershipRepository;
import healoworld.institution_service.presentation.dto.response.MyPartnershipResponse;
import healoworld.institution_service.presentation.dto.response.PartnershipResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PartnershipService {
    private final PartnershipRepository partnershipRepository;
    private final InstitutionRepository institutionRepository;
    private final PartnershipHistoryRepository partnershipHistoryRepository;


    // ===== 기존 (조회·내부 API) =====

    public boolean isActivePartnership(UUID requesterInstitutionId, UUID examInstitutionId) {
        return partnershipRepository.existsByRequesterInstitutionIdAndExamInstitutionIdAndStatus(
                requesterInstitutionId, examInstitutionId, PartnershipStatus.ACTIVE);
    }

    public List<UUID> getPartnerExamInstitutionIds(UUID requesterInstitutionId) {
        return partnershipRepository.findExamInstitutionIds(requesterInstitutionId, PartnershipStatus.ACTIVE);
    }

    public List<MyPartnershipResponse> getMyPartnerships(UUID myInstitutionId, PartnershipStatus status) {
        return partnershipRepository.findMyPartnerships(myInstitutionId, status).stream()
                .map(p -> MyPartnershipResponse.of(p, myInstitutionId))
                .toList();
    }

    // ===== 협약 요청 (의뢰기관 → 검사기관) =====

    @Transactional
    public PartnershipResponse request(UUID myInstitutionId, UUID examInstitutionId) {
        Institution requester = getInstitution(myInstitutionId);
        Institution exam = getInstitution(examInstitutionId);

        Optional<Partnership> existing =
                partnershipRepository.findByRequesterInstitutionIdAndExamInstitutionId(myInstitutionId, examInstitutionId);

        // 처음 요청 → 새 협약
        if (existing.isEmpty()) {
            return PartnershipResponse.from(createPartnership(requester, exam));
        }

        // 이미 진행 중 → 409
        Partnership partnership = existing.get();
        if (PartnershipStatus.inProgress().contains(partnership.getStatus())) {
            throw new BaseException(ErrorCode.PARTNERSHIP_ALREADY_IN_PROGRESS);
        }

        // 거절·종료된 협약 → 재요청
        PartnershipStatus from = partnership.getStatus();
        partnership.reRequest();
        saveHistory(partnership, from);
        return PartnershipResponse.from(partnership);
    }

    // ===== 승인·거절 (요청받은 검사기관만) =====

    @Transactional
    public PartnershipResponse approve(UUID partnershipId, UUID myInstitutionId) {
        Partnership partnership = getPartnership(partnershipId);
        validateExamInstitution(partnership, myInstitutionId);

        PartnershipStatus from = partnership.getStatus();
        partnership.approve();
        saveHistory(partnership, from);
        return PartnershipResponse.from(partnership);
    }

    @Transactional
    public PartnershipResponse reject(UUID partnershipId, UUID myInstitutionId) {
        Partnership partnership = getPartnership(partnershipId);
        validateExamInstitution(partnership, myInstitutionId);

        PartnershipStatus from = partnership.getStatus();
        partnership.reject();
        saveHistory(partnership, from);
        return PartnershipResponse.from(partnership);
    }

    // ===== 종료 (의뢰기관·검사기관·관리자) =====

    @Transactional
    public PartnershipResponse terminate(UUID partnershipId, UUID myInstitutionId, Role role) {
        Partnership partnership = getPartnership(partnershipId);
        if (role != Role.ADMIN && !partnership.isParty(myInstitutionId)) {
            throw new BaseException(ErrorCode.FORBIDDEN);
        }

        PartnershipStatus from = partnership.getStatus();
        partnership.terminate();
        saveHistory(partnership, from);
        return PartnershipResponse.from(partnership);
    }

    private Partnership createPartnership(Institution requester, Institution exam) {
        Partnership partnership = Partnership.request(requester, exam);   // 유형 검사는 엔티티가 함
        try {
            partnershipRepository.saveAndFlush(partnership);
        } catch (DataIntegrityViolationException e) {
            // 동시 요청으로 uk_partnership_pair 위반 → 실제로 행이 생겼으면 409, 아니면 원래 예외
            if (partnershipRepository.findByRequesterInstitutionIdAndExamInstitutionId(
                    requester.getId(), exam.getId()).isPresent()) {
                throw new BaseException(ErrorCode.PARTNERSHIP_ALREADY_IN_PROGRESS);
            }
            throw e;
        }
        saveHistory(partnership, null);   // 첫 요청: null → PENDING
        return partnership;
    }

    private void validateExamInstitution(Partnership partnership, UUID myInstitutionId) {
        if (!partnership.isExamInstitution(myInstitutionId)) {
            throw new BaseException(ErrorCode.FORBIDDEN);
        }
    }

    private void saveHistory(Partnership partnership, PartnershipStatus from) {
        partnershipHistoryRepository.save(
                PartnershipHistory.create(partnership, from, partnership.getStatus()));
    }

    private Partnership getPartnership(UUID partnershipId) {
        return partnershipRepository.findById(partnershipId)
                .filter(p -> p.getDeletedAt() == null)
                .orElseThrow(() -> new BaseException(ErrorCode.PARTNERSHIP_NOT_FOUND));
    }

    private Institution getInstitution(UUID institutionId) {
        return institutionRepository.findByIdAndDeletedAtIsNull(institutionId)
                .orElseThrow(() -> new BaseException(ErrorCode.INSTITUTION_NOT_FOUND));
    }
}
