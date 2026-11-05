package healoworld.institution_service.domain.entity;


import healoworld.institution_service.domain.enums.InstitutionType;
import healoworld.institution_service.domain.enums.PartnershipStatus;
import healoworld.institution_service.global.entity.BaseEntity;
import healoworld.institution_service.global.exception.BaseException;
import healoworld.institution_service.global.exception.ErrorCode;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "p_partnerships",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_partnership_pair",
                columnNames = {"requester_institution_id", "exam_institution_id"}
        ))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Partnership extends BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "requester_institution_id", nullable = false)
    private Institution requesterInstitution;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "exam_institution_id", nullable = false)
    private Institution examInstitution;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PartnershipStatus status;

    @Column(nullable = false)
    private LocalDateTime requestedAt;

    private LocalDateTime approvedAt;

    private LocalDateTime terminatedAt;

    public boolean isActive() {
        return status == PartnershipStatus.ACTIVE;
    }

    // 내 기관 기준으로 협약 상대 기관
    public Institution getPartnerOf(UUID myInstitutionId) {
        return requesterInstitution.getId().equals(myInstitutionId) ? examInstitution : requesterInstitution;
    }

    // 요청: 의뢰기관(requester) -> 검사기관(exam), PENDING으로 시작
    public static Partnership request(Institution requester, Institution exam) {
        if (requester.getType() != InstitutionType.REQUESTER || exam.getType() != InstitutionType.EXAM) {
            throw new BaseException(ErrorCode.INVALID_PARTNERSHIP_INSTITUTION);
        }

        Partnership partnership = new Partnership();
        partnership.requesterInstitution = requester;
        partnership.examInstitution = exam;
        partnership.status = PartnershipStatus.PENDING;
        partnership.requestedAt = LocalDateTime.now();

        return partnership;
    }

    // 재요청: 거절·종료된 협약을 다시 PENDING으로
    public void reRequest() {
        if (!this.status.isClosed()) {
            throw new BaseException(ErrorCode.INVALID_PARTNERSHIP_STATUS);
        }
        this.status = PartnershipStatus.PENDING;
        this.requestedAt = LocalDateTime.now();
        this.approvedAt = null;
        this.terminatedAt = null;
    }

    public void approve() {
        validateStatus(PartnershipStatus.PENDING);
        this.status = PartnershipStatus.ACTIVE;
        this.approvedAt = LocalDateTime.now();
    }

    public void reject() {
        validateStatus(PartnershipStatus.PENDING);
        this.status = PartnershipStatus.REJECTED;
    }

    public void terminate() {
        validateStatus(PartnershipStatus.ACTIVE);
        this.status = PartnershipStatus.TERMINATED;
        this.terminatedAt = LocalDateTime.now();
    }

    // 당사자 확인: 내 기관이 의뢰기관이거나 검사기관인가
    public boolean isParty(UUID institutionId) {
        return requesterInstitution.getId().equals(institutionId)
                || examInstitution.getId().equals(institutionId);
    }

    public boolean isExamInstitution(UUID institutionId) {
        return examInstitution.getId().equals(institutionId);
    }

    private void validateStatus(PartnershipStatus expected) {
        if (this.status != expected) {
            throw new BaseException(ErrorCode.INVALID_PARTNERSHIP_STATUS);
        }
    }

}
