package healoworld.referral_service.infrastructure.repository;

import healoworld.referral_service.domain.entity.Reservation;
import healoworld.referral_service.domain.enums.FailureReason;
import healoworld.referral_service.domain.enums.ReservationStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReservationRepository extends JpaRepository<Reservation, UUID>, ReservationRepositoryCustom {
    Optional<Reservation> findTopByReferralIdOrderByAttemptNoDesc(UUID referralId);

    Optional<Reservation> findByReferralIdAndAttemptNo(UUID id, int i);

    @Query("select r.referral.id from Reservation r where r.id = :id")
    Optional<UUID> findReferralIdById(@Param("id") UUID id);

    // 삭제된 의뢰의 예약 조회 시 500 Error를 막기 위해 fetch join
    @Query("select r from Reservation r join fetch r.referral where r.id = :id")
    Optional<Reservation> findByIdWithReferral(@Param("id") UUID id);

    // 아래 조건부 UPDATE는 WHERE의 현재 상태가 맞을 때만 바꾸고, 바꾼 행 수(0 또는 1)를 돌려준다.
    // 메서드 이름이 정하는 상태는 쿼리에 고정하고, 호출하는 곳마다 달라지는 값만 파라미터로 받는다.
    // 벌크 UPDATE는 Auditing을 거치지 않으므로 updatedAt·updatedBy를 직접 넣는다.

    // REQUESTED → HELD
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Reservation r
               set r.reservationStatus = healoworld.referral_service.domain.enums.ReservationStatus.HELD,
                   r.updatedAt = :now
             where r.id = :reservationId
               and r.reservationStatus = healoworld.referral_service.domain.enums.ReservationStatus.REQUESTED
               and r.deletedAt is null
            """)
    int holdIfRequested(@Param("reservationId") UUID reservationId,
                        @Param("now") LocalDateTime now);

    // HELD → CONFIRMED, 확정 시각 기록
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Reservation r
               set r.reservationStatus = healoworld.referral_service.domain.enums.ReservationStatus.CONFIRMED,
                   r.confirmedAt = :now,
                   r.updatedAt = :now
             where r.id = :reservationId
               and r.reservationStatus = healoworld.referral_service.domain.enums.ReservationStatus.HELD
               and r.deletedAt is null
            """)
    int confirmIfHeld(@Param("reservationId") UUID reservationId,
                      @Param("now") LocalDateTime now);

    // expected → FAILED. 실패 사유는 어느 단계에서 실패했는지(REQUESTED/HELD)에 따라 호출하는 곳이 넘긴다
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Reservation r
               set r.reservationStatus = healoworld.referral_service.domain.enums.ReservationStatus.FAILED,
                   r.failureReason = :failureReason,
                   r.endedAt = :now,
                   r.updatedAt = :now
             where r.id = :reservationId
               and r.reservationStatus = :expected
               and r.deletedAt is null
            """)
    int failIf(@Param("reservationId") UUID reservationId,
               @Param("now") LocalDateTime now,
               @Param("failureReason") FailureReason failureReason,
               @Param("expected") ReservationStatus expected);

    // 진행 중(REQUESTED/HELD/CONFIRMED) → BLOCKED(EQUIPMENT_FAILURE)
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Reservation r
               set r.reservationStatus = healoworld.referral_service.domain.enums.ReservationStatus.BLOCKED,
                   r.failureReason = healoworld.referral_service.domain.enums.FailureReason.EQUIPMENT_FAILURE,
                   r.endedAt = :now,
                   r.updatedAt = :now
             where r.id = :reservationId
               and r.reservationStatus in (healoworld.referral_service.domain.enums.ReservationStatus.REQUESTED,
                                           healoworld.referral_service.domain.enums.ReservationStatus.HELD,
                                           healoworld.referral_service.domain.enums.ReservationStatus.CONFIRMED)
               and r.deletedAt is null
            """)
    int blockIfActive(@Param("reservationId") UUID reservationId,
                      @Param("now") LocalDateTime now);

    // CONFIRMED → CANCELED. 확정 시각(confirmedAt)은 그대로 둔다
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Reservation r
               set r.reservationStatus = healoworld.referral_service.domain.enums.ReservationStatus.CANCELED,
                   r.endedAt = :now,
                   r.updatedAt = :now,
                   r.updatedBy = :userId
             where r.id = :reservationId
               and r.reservationStatus = healoworld.referral_service.domain.enums.ReservationStatus.CONFIRMED
               and r.deletedAt is null
            """)
    int cancelIfConfirmed(@Param("reservationId") UUID reservationId,
                          @Param("userId") UUID userId,
                          @Param("now") LocalDateTime now);

    // 허용 상태(종료된 예약)일 때만 soft delete
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Reservation r
               set r.deletedAt = :now,
                   r.deletedBy = :userId,
                   r.updatedAt = :now,
                   r.updatedBy = :userId
             where r.id = :reservationId
               and r.reservationStatus in :allowed
               and r.deletedAt is null
            """)
    int softDeleteIf(@Param("reservationId") UUID reservationId,
                     @Param("userId") UUID userId,
                     @Param("now") LocalDateTime now,
                     @Param("allowed") Collection<ReservationStatus> allowed);

    @Query("""
                 select new healoworld.referral_service.infrastructure.repository.ReconciliationTarget(r.id, r.slotId, r.reservationStatus)
                 from Reservation r
                 where r.reservationStatus in :statuses
                   and r.updatedAt < :updatedBefore
                   and r.id > :lastId
                   and r.deletedAt is null
                   and not exists (select 1 from OutboxEvent o
                                      where o.aggregateId = r.id
                                        and o.publishedAt is null)
                   order by r.id
            """)
    List<ReconciliationTarget> findStaleInProgress(
            @Param("statuses") Collection<ReservationStatus> statuses,
            @Param("updatedBefore") LocalDateTime updatedBefore,
            @Param("lastId") UUID lastId,
            Pageable pageable);

    @Query("""
                 select new healoworld.referral_service.infrastructure.repository.ReconciliationTarget(r.id, r.slotId, r.reservationStatus)
                 from Reservation r
                 where r.reservationStatus = healoworld.referral_service.domain.enums.ReservationStatus.CONFIRMED
                   and r.startTime > :now
                   and r.id > :lastId
                   and r.deletedAt is null
                   and not exists (select 1 from OutboxEvent o
                                      where o.aggregateId = r.id
                                        and o.publishedAt is null)
                   order by r.id
            """)
    List<ReconciliationTarget> findUpcomingConfirmed(
            @Param("now") LocalDateTime now,
            @Param("lastId") UUID lastId,
            Pageable pageable);

    @Query("""
                 select new healoworld.referral_service.infrastructure.repository.ReconciliationTarget(r.id, r.slotId, r.reservationStatus)
                 from Reservation r
                 where r.reservationStatus in :statuses
                   and r.endedAt > :endedAfter
                   and r.id > :lastId
                   and r.deletedAt is null
                   and not exists (select 1 from OutboxEvent o
                                      where o.aggregateId = r.id
                                        and o.publishedAt is null)
                   order by r.id
            """)
    List<ReconciliationTarget> findRecentlyEnded(
            @Param("statuses") Collection<ReservationStatus> statuses,
            @Param("endedAfter") LocalDateTime endedAfter,
            @Param("lastId") UUID lastId,
            Pageable pageable);
}
