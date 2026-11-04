package healoworld.institution_service.infrastructure.repository;

import healoworld.institution_service.domain.entity.EquipmentExamType;
import healoworld.institution_service.presentation.dto.response.EquipmentExamTypeResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EquipmentExamTypeRepository extends JpaRepository<EquipmentExamType, UUID> {

    Optional<EquipmentExamType> findByEquipmentIdAndExamTypeId(UUID equipmentId, UUID examTypeId);

    Optional<EquipmentExamType> findByEquipmentIdAndExamTypeIdAndDeletedAtIsNull(UUID equipmentId, UUID examTypeId);

    boolean existsByEquipmentIdAndExamTypeIdAndDeletedAtIsNull(UUID equipmentId, UUID examTypeId);

    List<EquipmentExamType> findAllByEquipmentIdAndDeletedAtIsNull(UUID equipmentId);

    @Query(
            value = """
            select new healoworld.institution_service.presentation.dto.response.EquipmentExamTypeResponse(
                eet.id,
                eet.equipmentId,
                et.id,
                et.name,
                et.modality,
                eet.durationMinutes
            )
            from EquipmentExamType eet, ExamType et
            where eet.examTypeId = et.id
            and eet.equipmentId = :equipmentId
            and eet.deletedAt is null
            and et.deletedAt is null
            order by et.name asc, et.id asc
            """,
            countQuery = """
            select count(eet)
            from EquipmentExamType eet, ExamType et
            where eet.examTypeId = et.id
            and eet.equipmentId = :equipmentId
            and eet.deletedAt is null
            and et.deletedAt is null
            """
    )
    Page<EquipmentExamTypeResponse> findSupportedExamTypes(
            @Param("equipmentId") UUID equipmentId,
            Pageable pageable
    );
}
