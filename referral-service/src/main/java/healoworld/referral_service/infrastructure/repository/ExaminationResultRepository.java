package healoworld.referral_service.infrastructure.repository;

import healoworld.referral_service.domain.entity.ExaminationResult;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ExaminationResultRepository extends JpaRepository<ExaminationResult, UUID> {

    // examination.id 경로로 조회한다 (연관 엔티티를 로딩하지 않고 FK 컬럼으로 검사)
    boolean existsByExaminationId(UUID examinationId);

    long countByExaminationId(UUID examinationId);
}
