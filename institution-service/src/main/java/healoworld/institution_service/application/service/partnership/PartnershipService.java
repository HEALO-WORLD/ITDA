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
import org.hibernate.exception.ConstraintViolationException;
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
    private static final String UNIQUE_PARTNERSHIP_PAIR = "uk_partnership_pair";

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
            // 동시 요청으로 uk_partnership_pair 위반 -> 409
            // 오류가 난 트랜잭션에서는 PostgreSQL이 이후 쿼리를 거부하므로 다시 조회하지 않고 제약 이름으로 판단
            throw translateConstraint(e);
        }
        saveHistory(partnership, null);   // 첫 요청: null → PENDING
        return partnership;
    }

    private RuntimeException translateConstraint(DataIntegrityViolationException exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation                     // 제약 위반인지
                    && "23505".equals(violation.getSQLState())                              // 그 중 unique위반인지(외래키 위반 등과 구분)
                    && UNIQUE_PARTNERSHIP_PAIR.equals(violation.getConstraintName())) {     // 두 기관 쌍 제약인지(다른 unique 제약과 구분)
                return new BaseException(ErrorCode.PARTNERSHIP_ALREADY_IN_PROGRESS);
            }
        }
        return exception;
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
