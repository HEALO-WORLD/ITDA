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

    // 장비 장애 현황 집계: 검사기관의 현재 재조정 필요 의뢰 수
    // - 대상: 지금 NEEDS_RESCHEDULE인 의뢰 (삭제된 의뢰 제외). 의뢰마다 한 행이므로 같은 의뢰는 한 번만 센다
    // - 기관: 의뢰의 가장 최근 BLOCKED 예약에 남은 검사기관 스냅샷(exam_institution_id)
    //   NEEDS_RESCHEDULE는 항상 예약 차단(BLOCKED)으로 시작하고, 재예약 실패는 차단을 새로 만들지 않으므로 가장 최근 차단이 현재 재조정의 원인이다
    //   (장애 처리·SlotsBlocked 두 경로 모두 예약을 BLOCKED로 바꾸므로 IncidentReferral 대신 예약을 기준으로 한다)
    // - 최근 순서: 예약 시도 번호(attempt_no)가 큰 것. 과거 결함으로 같은 번호가 두 번 쓰인 경우에는 나중에 만들어진 예약(created_at)
    //   (번호 재사용은 앞 예약이 삭제된 뒤에만 생기므로 생성 시각 순서가 실제 순서다. 생성 시각까지 같으면 순서를 판단할 수 없다)
    // - native SQL을 쓰는 이유: 소프트 삭제된 BLOCKED 예약도 차단 이력으로 봐야 하는데,
    //   Reservation의 @SQLRestriction("deleted_at IS NULL")이 JPQL에는 자동으로 붙어 삭제된 예약이 빠진다. DISTINCT ON도 PostgreSQL 문법이다
    @Query(value = """
            select count(*)
              from (select distinct on (r.referral_id) r.referral_id, r.exam_institution_id
                      from p_reservations r
                      join p_referrals rf on rf.id = r.referral_id
                     where r.reservation_status = 'BLOCKED'
                       and rf.referral_status = 'NEEDS_RESCHEDULE'
                       and rf.deleted_at is null
                     order by r.referral_id, r.attempt_no desc, r.created_at desc nulls last) latest
             where latest.exam_institution_id = :institutionId
            """, nativeQuery = true)
    long countNeedsRescheduleByLatestBlockedInstitution(@Param("institutionId") UUID institutionId);
}
