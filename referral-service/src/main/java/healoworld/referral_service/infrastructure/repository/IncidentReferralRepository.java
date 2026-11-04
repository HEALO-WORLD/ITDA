package healoworld.referral_service.infrastructure.repository;

import healoworld.referral_service.domain.entity.IncidentReferral;
import healoworld.referral_service.domain.enums.IncidentProcessingStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public interface IncidentReferralRepository extends JpaRepository<IncidentReferral, UUID> {

    // =========================================================
    // 1. 장애별 후속 처리 작업 조회
    // =========================================================

    // 장애별 영향 의뢰 목록 조회
    Page<IncidentReferral> findByIncident_Id(
            UUID incidentId,
            Pageable pageable
    );

    // 장애별 처리 상태에 따른 영향 의뢰 목록 조회
    Page<IncidentReferral> findByIncident_IdAndProcessingStatus(
            UUID incidentId,
            IncidentProcessingStatus status,
            Pageable pageable
    );

    // 장애별 처리 상태 건수 조회
    long countByIncident_IdAndProcessingStatus(
            UUID incidentId,
            IncidentProcessingStatus status
    );

    // 동일 장애·예약에 대한 중복 작업 존재 여부 확인
    // 최종 중복 방지는 UNIQUE(incident_id, reservation_id) 제약 조건으로 보장
    boolean existsByIncident_IdAndReservationId(
            UUID incidentId,
            UUID reservationId
    );


    // =========================================================
    // 2. 재처리 및 스케줄러 대상 조회
    // =========================================================

    // 특정 장애의 재처리 대상 조회
    // - FAILED: 재시도 시각이 지난 작업
    // - PROCESSING: 작업 잠금이 만료된 작업
    // TODO: 최대 재시도 횟수 제한 정책 추가
    @Query("""
            SELECT ir
            FROM IncidentReferral ir
            WHERE ir.incident.id = :incidentId
              AND (
                  (
                      ir.processingStatus = healoworld.referral_service.domain.enums.IncidentProcessingStatus.FAILED
                      AND (ir.nextRetryAt IS NULL OR ir.nextRetryAt <= :now)
                  )
                  OR (
                      ir.processingStatus = healoworld.referral_service.domain.enums.IncidentProcessingStatus.PROCESSING
                      AND ir.lockedUntil < :now
                  )
              )
            """)
    List<IncidentReferral> findRetryTargets(
            @Param("incidentId") UUID incidentId,
            @Param("now") LocalDateTime now,
            Pageable pageable
    );

    // 전체 장애에서 현재 실행 가능한 작업 ID 조회
    // - PENDING: 최초 처리 대기
    // - FAILED: 재시도 시각 도달
    // - PROCESSING: 작업 잠금 만료
    // - SUCCEEDED: 조회 제외
    //
    // Pageable로 한 번에 가져올 작업 수 제한
    @Query("""
            SELECT ir.id
            FROM IncidentReferral ir
            WHERE ir.processingStatus = :pending
               OR (
                   ir.processingStatus = :failed
                   AND (ir.nextRetryAt IS NULL OR ir.nextRetryAt <= :now)
               )
               OR (
                   ir.processingStatus = :processing
                   AND ir.lockedUntil < :now
               )
            ORDER BY ir.createdAt ASC, ir.id ASC
            """)
    List<UUID> findReadyTaskIds(
            @Param("now") LocalDateTime now,
            @Param("pending") IncidentProcessingStatus pending,
            @Param("failed") IncidentProcessingStatus failed,
            @Param("processing") IncidentProcessingStatus processing,
            Pageable pageable
    );


    // =========================================================
    // 3. 작업 선점
    // =========================================================

    // - PENDING: 즉시 선점 가능
    // - FAILED: 재시도 시각이 지난 경우만 선점 가능
    // - PROCESSING: 잠금이 만료된 경우만 재선점 가능
    // - 선점 성공 시 1, 실패 시 0 반환
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE IncidentReferral ir
            SET ir.processingStatus = healoworld.referral_service.domain.enums.IncidentProcessingStatus.PROCESSING,
                ir.retryCount = CASE
                    WHEN ir.processingStatus = healoworld.referral_service.domain.enums.IncidentProcessingStatus.FAILED
                    THEN ir.retryCount + 1
                    ELSE ir.retryCount
                END,
                ir.lockedUntil = :lockedUntil,
                ir.updatedAt = :now
            WHERE ir.id = :id
              AND (
                  ir.processingStatus = healoworld.referral_service.domain.enums.IncidentProcessingStatus.PENDING
                  OR (
                      ir.processingStatus = healoworld.referral_service.domain.enums.IncidentProcessingStatus.FAILED
                      AND (ir.nextRetryAt IS NULL OR ir.nextRetryAt <= :now)
                  )
                  OR (
                      ir.processingStatus = healoworld.referral_service.domain.enums.IncidentProcessingStatus.PROCESSING
                      AND ir.lockedUntil < :now
                  )
              )
            """)
    int claim(
            @Param("id") UUID id,
            @Param("now") LocalDateTime now,
            @Param("lockedUntil") LocalDateTime lockedUntil
    );


    // =========================================================
    // 4. 작업 성공 처리
    // =========================================================

    // PROCESSING → SUCCEEDED
    // - 처리 결과 및 완료 시각 기록
    // - 잠금 및 재시도 시각 초기화
    // - 선점 당시 lockedUntil이 일치할 때만 갱신
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE IncidentReferral ir
            SET ir.processingStatus = healoworld.referral_service.domain.enums.IncidentProcessingStatus.SUCCEEDED,
                ir.resultCode = :resultCode,
                ir.processedAt = :now,
                ir.lockedUntil = null,
                ir.nextRetryAt = null,
                ir.updatedAt = :now
            WHERE ir.id = :id
              AND ir.processingStatus = healoworld.referral_service.domain.enums.IncidentProcessingStatus.PROCESSING
              AND ir.lockedUntil = :lockedUntil
            """)
    int markSucceeded(
            @Param("id") UUID id,
            @Param("lockedUntil") LocalDateTime lockedUntil,
            @Param("resultCode") String resultCode,
            @Param("now") LocalDateTime now
    );


    // =========================================================
    // 5. 작업 실패 처리
    // =========================================================

    // PROCESSING → FAILED
    // - 실패 원인 및 다음 재시도 시각 기록
    // - 잠금 해제
    // - 선점 당시 lockedUntil이 일치할 때만 갱신
    //
    // TODO: 재시도 백오프 및 최대 재시도 횟수 정책 추가
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE IncidentReferral ir
            SET ir.processingStatus = healoworld.referral_service.domain.enums.IncidentProcessingStatus.FAILED,
                ir.lastError = :lastError,
                ir.nextRetryAt = :nextRetryAt,
                ir.lockedUntil = null,
                ir.updatedAt = :now
            WHERE ir.id = :id
              AND ir.processingStatus = healoworld.referral_service.domain.enums.IncidentProcessingStatus.PROCESSING
              AND ir.lockedUntil = :lockedUntil
            """)
    int markFailed(
            @Param("id") UUID id,
            @Param("lockedUntil") LocalDateTime lockedUntil,
            @Param("lastError") String lastError,
            @Param("nextRetryAt") LocalDateTime nextRetryAt,
            @Param("now") LocalDateTime now
    );
}
