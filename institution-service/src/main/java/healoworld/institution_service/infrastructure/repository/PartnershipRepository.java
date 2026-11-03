package healoworld.institution_service.infrastructure.repository;

import healoworld.institution_service.domain.entity.Partnership;
import healoworld.institution_service.domain.enums.PartnershipStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface PartnershipRepository extends JpaRepository<Partnership, UUID> {

    // Referral: 두 기관이 ACTIVE 협약인가
    boolean existsByRequesterInstitutionIdAndExamInstitutionIdAndStatus(
            UUID requesterInstitutionId, UUID examInstitutionId, PartnershipStatus status
    );

    // Slot: 이 의뢰기관과 ACTIVE 협약인 검사기관 ID 목록
    @Query("select p.examInstitution.id from Partnership p " +
            "where p.requesterInstitution.id = :requesterInstitutionId and p.status = :status")
    List<UUID> findExamInstitutionIds(UUID requesterInstitutionId, PartnershipStatus status);

    @Query("select p from Partnership p " +
            "join fetch p.requesterInstitution " +
            "join fetch p.examInstitution " +
            "where (p.requesterInstitution.id = :institutionId or p.examInstitution.id = :institutionId) " +
            "and (:status is null or p.status = :status) " +
            "order by p.requestedAt")
    List<Partnership> findMyPartnerships(UUID institutionId, PartnershipStatus status);

}
