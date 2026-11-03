package healoworld.institution_service.infrastructure.repository;

import healoworld.institution_service.domain.entity.ExamType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ExamTypeRepository extends JpaRepository<ExamType, UUID> {

    Optional<ExamType> findByIdAndDeletedAtIsNull(UUID id);
}