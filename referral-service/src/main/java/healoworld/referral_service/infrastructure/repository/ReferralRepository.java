package healoworld.referral_service.infrastructure.repository;

import healoworld.referral_service.domain.entity.Referral;
import healoworld.referral_service.domain.enums.ReferralStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

public interface ReferralRepository extends JpaRepository<Referral, UUID>, ReferralRepositoryCustom {
    Optional<Referral> findByIdempotencyKey(UUID idempotencyKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Referral r where r.id = :id")
    Optional<Referral> findByIdForUpdate(@Param("id") UUID id);

    // 아래 조건부 UPDATE는 WHERE의 현재 상태가 맞을 때만 바꾸고, 바꾼 행 수(0 또는 1)를 돌려준다.
    // 메서드 이름이 정하는 상태는 쿼리에 고정하고, 호출하는 곳마다 달라지는 값만 파라미터로 받는다.
    // 벌크 UPDATE는 Auditing을 거치지 않으므로 updatedAt·updatedBy를 직접 넣는다.

    // PENDING → CONFIRMED
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Referral r
               set r.referralStatus = healoworld.referral_service.domain.enums.ReferralStatus.CONFIRMED,
                   r.updatedAt = :now
             where r.id = :referralId
               and r.referralStatus = healoworld.referral_service.domain.enums.ReferralStatus.PENDING
               and r.deletedAt is null
            """)
    int confirmIfPending(@Param("referralId") UUID referralId,
                         @Param("now") LocalDateTime now);

    // PENDING → FAILED
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Referral r
               set r.referralStatus = healoworld.referral_service.domain.enums.ReferralStatus.FAILED,
                   r.updatedAt = :now
             where r.id = :referralId
               and r.referralStatus = healoworld.referral_service.domain.enums.ReferralStatus.PENDING
               and r.deletedAt is null
            """)
    int failIfPending(@Param("referralId") UUID referralId,
                      @Param("now") LocalDateTime now);

    // allowed → NEEDS_RESCHEDULE. 재예약 실패는 PENDING만, 장비 장애는 PENDING·CONFIRMED를 넘긴다
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Referral r
               set r.referralStatus = healoworld.referral_service.domain.enums.ReferralStatus.NEEDS_RESCHEDULE,
                   r.updatedAt = :now
             where r.id = :referralId
               and r.referralStatus in :allowed
               and r.deletedAt is null
            """)
    int requestRescheduleIfIn(@Param("referralId") UUID referralId,
                              @Param("now") LocalDateTime now,
                              @Param("allowed") Collection<ReferralStatus> allowed);

    // CONFIRMED·NEEDS_RESCHEDULE → CANCELED
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Referral r
               set r.referralStatus = healoworld.referral_service.domain.enums.ReferralStatus.CANCELED,
                   r.updatedAt = :now,
                   r.updatedBy = :userId
             where r.id = :referralId
               and r.referralStatus in (healoworld.referral_service.domain.enums.ReferralStatus.CONFIRMED,
                                        healoworld.referral_service.domain.enums.ReferralStatus.NEEDS_RESCHEDULE)
               and r.deletedAt is null
            """)
    int cancelIfCancelable(@Param("referralId") UUID referralId,
                           @Param("userId") UUID userId,
                           @Param("now") LocalDateTime now);

    // NEEDS_RESCHEDULE → PENDING, 현재 예약을 새 예약으로 교체
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Referral r
               set r.referralStatus = healoworld.referral_service.domain.enums.ReferralStatus.PENDING,
                   r.currentReservationId = :newReservationId,
                   r.updatedAt = :now,
                   r.updatedBy = :userId
             where r.id = :referralId
               and r.referralStatus = healoworld.referral_service.domain.enums.ReferralStatus.NEEDS_RESCHEDULE
               and r.deletedAt is null
            """)
    int rescheduleIfNeedsReschedule(@Param("referralId") UUID referralId,
                                    @Param("newReservationId") UUID newReservationId,
                                    @Param("userId") UUID userId,
                                    @Param("now") LocalDateTime now);

    // 허용 상태(종료된 의뢰)일 때만 soft delete
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Referral r
               set r.deletedAt = :now,
                   r.deletedBy = :userId,
                   r.updatedAt = :now,
                   r.updatedBy = :userId
             where r.id = :referralId
               and r.referralStatus in :allowed
               and r.deletedAt is null
            """)
    int softDeleteIf(@Param("referralId") UUID referralId,
                     @Param("userId") UUID userId,
                     @Param("now") LocalDateTime now,
                     @Param("allowed") Collection<ReferralStatus> allowed);
}
