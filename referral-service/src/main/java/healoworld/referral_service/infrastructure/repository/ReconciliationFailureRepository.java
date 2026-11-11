package healoworld.referral_service.infrastructure.repository;

import healoworld.referral_service.domain.entity.ReconciliationFailure;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

public interface ReconciliationFailureRepository extends JpaRepository<ReconciliationFailure, UUID> {

    // 예약당 1건으로 실패를 기록한다 (UNIQUE reservation_id 충돌 시 횟수 +1, 마지막 실패 정보 갱신)
    // status: 이번 실패가 복구 불가능(MANUAL_REVIEW)이거나, 이미 MANUAL_REVIEW거나, 누적 횟수가 maxAttempts에 닿으면 MANUAL_REVIEW
    //         그 밖에는 RETRYING. 한 번 MANUAL_REVIEW가 되면 다시 RETRYING으로 내려가지 않는다
    // 동시에 같은 예약을 기록해도 UNIQUE 제약과 ON CONFLICT로 행은 1건만 남는다
    @Modifying
    @Query(value = """
            insert into p_reconciliation_failures as f
                   (id, reservation_id, result_type, status, failure_count, error_code, failure_reason,
                    first_failed_at, last_failed_at)
            values (:id, :reservationId, :resultType,
                    case when :manualReview or :maxAttempts <= 1 then 'MANUAL_REVIEW' else 'RETRYING' end,
                    1, :errorCode, :failureReason, :now, :now)
            on conflict (reservation_id) do update
               set failure_count  = f.failure_count + 1,
                   result_type    = excluded.result_type,
                   error_code     = excluded.error_code,
                   failure_reason = excluded.failure_reason,
                   last_failed_at = excluded.last_failed_at,
                   status = case when :manualReview
                                   or f.status = 'MANUAL_REVIEW'
                                   or f.failure_count + 1 >= :maxAttempts
                                 then 'MANUAL_REVIEW' else 'RETRYING' end
            """, nativeQuery = true)
    int upsertFailure(@Param("id") UUID id,
                      @Param("reservationId") UUID reservationId,
                      @Param("resultType") String resultType,
                      @Param("manualReview") boolean manualReview,
                      @Param("maxAttempts") int maxAttempts,
                      @Param("errorCode") String errorCode,
                      @Param("failureReason") String failureReason,
                      @Param("now") LocalDateTime now);

    Optional<ReconciliationFailure> findByReservationId(UUID reservationId);

    // 일시 오류 뒤 복구에 성공하면 RETRYING 기록을 지운다 (MANUAL_REVIEW는 담당자 확인 전까지 남긴다)
    @Modifying
    @Query("""
            delete from ReconciliationFailure f
             where f.reservationId = :reservationId
               and f.status = healoworld.referral_service.domain.enums.ReconciliationFailureStatus.RETRYING
            """)
    int deleteRetryingByReservationId(@Param("reservationId") UUID reservationId);
}
