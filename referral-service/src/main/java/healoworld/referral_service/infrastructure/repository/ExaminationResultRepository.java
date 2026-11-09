package healoworld.referral_service.infrastructure.repository;

import healoworld.referral_service.domain.entity.ExaminationResult;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ExaminationResultRepository
        extends JpaRepository<ExaminationResult, UUID> {

    // 검사 1건의 결과 등록 여부 조회
    boolean existsByExaminationId(UUID examinationId);

    // 검사 1건에 연결된 결과 개수 조회
    long countByExaminationId(UUID examinationId);

    // 검사 ID로 결과 조회
    Optional<ExaminationResult> findByExamination_Id(UUID examinationId);

    // 여러 검사 중 결과가 등록된 검사 ID를 한 번에 조회
    @Query("""
            SELECT er.examination.id
            FROM ExaminationResult er
            WHERE er.examination.id IN :examinationIds
            """)
    List<UUID> findRegisteredExaminationIds(
            @Param("examinationIds") Collection<UUID> examinationIds
    );
}