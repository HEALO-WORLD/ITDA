package healoworld.institution_service.infrastructure.repository;

import healoworld.institution_service.domain.entity.ExamType;
import healoworld.institution_service.domain.enums.Modality;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface ExamTypeRepository extends JpaRepository<ExamType, UUID> {

    Optional<ExamType> findByIdAndDeletedAtIsNull(UUID id);

    boolean existsByNameAndModalityAndDeletedAtIsNull(String name, Modality modality);

    boolean existsByNameAndModalityAndDeletedAtIsNullAndIdNot(String name, Modality modality, UUID id);

    @Query("""
        select e
        from ExamType e
        where e.deletedAt is null
        and (:modality is null or e.modality = :modality)
        order by e.name asc, e.id asc
        """)
    Page<ExamType> findExamTypes(
            @Param("modality") Modality modality,
            Pageable pageable
    );
}
