package healoworld.institution_service.infrastructure.repository;

import healoworld.institution_service.domain.entity.Equipment;
import healoworld.institution_service.domain.enums.EquipmentStatus;
import healoworld.institution_service.domain.enums.InstitutionType;
import healoworld.institution_service.domain.enums.Modality;
import healoworld.institution_service.domain.enums.PartnershipStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface EquipmentRepository extends JpaRepository<Equipment, UUID> {

    Optional<Equipment> findByIdAndDeletedAtIsNull(UUID id);

    @Query("""
        select e
        from Equipment e
        where e.institutionId = :institutionId
        and e.deletedAt is null
        and (:modality is null or e.modality = :modality)
        order by e.name asc, e.id asc
        """)
    Page<Equipment> findEquipments(
            @Param("institutionId") UUID institutionId,
            @Param("modality") Modality modality,
            Pageable pageable
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select e from Equipment e
        where e.id = :equipmentId and e.deletedAt is null
        """)
    Optional<Equipment> findForUpdateById(@Param("equipmentId") UUID equipmentId);

    @Query("""
        select e.id
        from Equipment e, Institution owner
        where owner.id = e.institutionId
        and e.deletedAt is null
        and owner.deletedAt is null
        and owner.active = true
        and owner.type = :examInstitutionType
        and (:institutionId is null or e.institutionId = :institutionId)
        and (:equipmentStatus is null or e.status = :equipmentStatus)
        and (
            :unrestricted = true
            or (:requester = false and e.institutionId = :callerInstitutionId)
            or (
                :requester = true
                and exists (
                    select p.id from Partnership p
                    where p.requesterInstitution.id = :callerInstitutionId
                    and p.examInstitution.id = owner.id
                    and p.status = :activeStatus
                    and p.deletedAt is null
                )
            )
        )
        """)
    Set<UUID> findVisibleEquipmentIds(
            @Param("callerInstitutionId") UUID callerInstitutionId,
            @Param("unrestricted") boolean unrestricted,
            @Param("requester") boolean requester,
            @Param("institutionId") UUID institutionId,
            @Param("equipmentStatus") EquipmentStatus equipmentStatus,
            @Param("examInstitutionType") InstitutionType examInstitutionType,
            @Param("activeStatus") PartnershipStatus activeStatus
    );
}