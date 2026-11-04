package healoworld.institution_service.infrastructure.repository;

import healoworld.institution_service.domain.entity.Partnership;
import healoworld.institution_service.domain.enums.PartnershipStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
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

    boolean existsByRequesterInstitutionIdAndExamInstitutionIdAndStatusAndDeletedAtIsNull(
            UUID requesterInstitutionId, UUID examInstitutionId, PartnershipStatus status
    );

    @Query("select count(p) > 0 from Partnership p " +
            "where (p.requesterInstitution.id = :institutionId or p.examInstitution.id = :institutionId) " +
            "and p.status in :statuses " +
            "and p.deletedAt is null")
    boolean existsByInstitutionIdAndStatusIn(@Param("institutionId") UUID institutionId,
                                             @Param("statuses") Collection<PartnershipStatus> statuses);

    @Query("select count(p) > 0 from Partnership p " +
            "where ((p.requesterInstitution.id = :institutionId and p.examInstitution.id = :otherInstitutionId) " +
            "    or (p.requesterInstitution.id = :otherInstitutionId and p.examInstitution.id = :institutionId)) " +
            "and p.status in :statuses " +
            "and p.deletedAt is null")
    boolean existsBetweenInstitutionsAndStatusIn(@Param("institutionId") UUID institutionId,
                                                 @Param("otherInstitutionId") UUID otherInstitutionId,
                                                 @Param("statuses") Collection<PartnershipStatus> statuses);
}
