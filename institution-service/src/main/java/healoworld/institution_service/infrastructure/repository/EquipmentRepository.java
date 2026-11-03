package healoworld.institution_service.infrastructure.repository;

import healoworld.institution_service.domain.entity.Equipment;
import healoworld.institution_service.domain.enums.Modality;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
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
}