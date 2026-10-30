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

    // 장애별 영향 의뢰 목록 (전체 / 처리 상태별)
    Page<IncidentReferral> findByIncident_Id(UUID incidentId, Pageable pageable);

    Page<IncidentReferral> findByIncident_IdAndProcessingStatus(UUID incidentId, IncidentProcessingStatus status,
                                                                Pageable pageable);

    // 장애 처리 요약의 상태별 건수
    long countByIncident_IdAndProcessingStatus(UUID incidentId, IncidentProcessingStatus status);

    // 같은 장애·예약 작업이 이미 있는지 사전 확인. 최종 중복 방지는 (incident_id, reservation_id) UNIQUE 제약
    boolean existsByIncident_IdAndReservationId(UUID incidentId, UUID reservationId);

    // 재처리 대상: 재시도 시각이 된 FAILED + 작업자가 잠금 시간 안에 끝내지 못한(잠금 만료) PROCESSING
    // TODO: 최대 재시도 횟수 제외 조건, 장애 구분 없는 전체 스캔(스케줄러용) 필요 여부는 재처리 정책 확정 후 결정
    @Query("""
            select ir from IncidentReferral ir
             where ir.incident.id = :incidentId
               and ((ir.processingStatus = healoworld.referral_service.domain.enums.IncidentProcessingStatus.FAILED
                     and (ir.nextRetryAt is null or ir.nextRetryAt <= :now))
                 or (ir.processingStatus = healoworld.referral_service.domain.enums.IncidentProcessingStatus.PROCESSING
                     and ir.lockedUntil < :now))
            """)
    List<IncidentReferral> findRetryTargets(@Param("incidentId") UUID incidentId, @Param("now") LocalDateTime now,
                                            Pageable pageable);

    // 아래 조건부 UPDATE는 WHERE 조건이 맞을 때만 갱신하고, 갱신한 행 수(0 또는 1)를 돌려준다
    // 같은 행을 동시에 갱신하면 DB 행 잠금으로 순서가 정해지고, 뒤 요청은 바뀐 값으로 조건을 다시 검사해 0건이 된다

    // 작업 선점: PENDING·FAILED 또는 잠금이 만료된 PROCESSING → PROCESSING, lockedUntil까지 선점
    // 1을 받은 작업자만 후속 처리를 실행한다. FAILED에서 가져가면 재시도이므로 retry_count를 1 올린다
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update IncidentReferral ir
               set ir.processingStatus = healoworld.referral_service.domain.enums.IncidentProcessingStatus.PROCESSING,
                   ir.retryCount = case
                       when ir.processingStatus = healoworld.referral_service.domain.enums.IncidentProcessingStatus.FAILED
                       then ir.retryCount + 1 else ir.retryCount end,
                   ir.lockedUntil = :lockedUntil, ir.updatedAt = :now
             where ir.id = :id
               and (ir.processingStatus in (healoworld.referral_service.domain.enums.IncidentProcessingStatus.PENDING,
                                            healoworld.referral_service.domain.enums.IncidentProcessingStatus.FAILED)
                 or (ir.processingStatus = healoworld.referral_service.domain.enums.IncidentProcessingStatus.PROCESSING
                     and ir.lockedUntil < :now))
            """)
    int claim(@Param("id") UUID id, @Param("now") LocalDateTime now, @Param("lockedUntil") LocalDateTime lockedUntil);

    // 아래 두 메서드의 lockedUntil은 claim에 넘겼던 값과 같아야 한다 (DB 저장 정밀도에 맞춰 마이크로초로 자른 값)
    // 잠금이 만료돼 다른 작업자가 다시 선점했다면 lockedUntil이 달라 0건이 되므로, 늦게 끝난 작업자가 결과를 덮어쓰지 못한다

    // PROCESSING → SUCCEEDED. 선점을 풀고 최종 처리 시각을 남긴다
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update IncidentReferral ir
               set ir.processingStatus = healoworld.referral_service.domain.enums.IncidentProcessingStatus.SUCCEEDED,
                   ir.resultCode = :resultCode, ir.processedAt = :now,
                   ir.lockedUntil = null, ir.nextRetryAt = null, ir.updatedAt = :now
             where ir.id = :id
               and ir.processingStatus = healoworld.referral_service.domain.enums.IncidentProcessingStatus.PROCESSING
               and ir.lockedUntil = :lockedUntil
            """)
    int markSucceeded(@Param("id") UUID id, @Param("lockedUntil") LocalDateTime lockedUntil,
                      @Param("resultCode") String resultCode, @Param("now") LocalDateTime now);

    // PROCESSING → FAILED. 선점을 풀고 실패 원인과 다음 재시도 시각을 남긴다 (lastError는 500자 이내로 넘긴다)
    // TODO: nextRetryAt 계산(백오프)과 실패 시 resultCode 기록 여부는 재처리 정책 확정 후 결정
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update IncidentReferral ir
               set ir.processingStatus = healoworld.referral_service.domain.enums.IncidentProcessingStatus.FAILED,
                   ir.lastError = :lastError, ir.nextRetryAt = :nextRetryAt,
                   ir.lockedUntil = null, ir.updatedAt = :now
             where ir.id = :id
               and ir.processingStatus = healoworld.referral_service.domain.enums.IncidentProcessingStatus.PROCESSING
               and ir.lockedUntil = :lockedUntil
            """)
    int markFailed(@Param("id") UUID id, @Param("lockedUntil") LocalDateTime lockedUntil,
                   @Param("lastError") String lastError, @Param("nextRetryAt") LocalDateTime nextRetryAt,
                   @Param("now") LocalDateTime now);
}
