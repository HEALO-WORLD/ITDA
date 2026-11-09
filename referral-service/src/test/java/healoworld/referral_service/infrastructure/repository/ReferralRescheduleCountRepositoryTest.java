package healoworld.referral_service.infrastructure.repository;

import healoworld.referral_service.domain.entity.Referral;
import healoworld.referral_service.domain.entity.Reservation;
import healoworld.referral_service.domain.enums.FailureReason;
import healoworld.referral_service.domain.enums.ReferralStatus;
import healoworld.referral_service.domain.enums.ReservationStatus;
import healoworld.referral_service.support.RepositoryTestSupport;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ReferralRepository.countNeedsRescheduleByLatestBlockedInstitution()을 실제 PostgreSQL로 검증한다.
 * <p>
 * - 의뢰·예약을 원하는 최종 상태로 직접 저장해 집계 조건(상태, 최근 차단 예약, 소프트 삭제, 레거시 중복 번호)을 확인한다
 *   (실제 장애 처리·재예약 흐름으로 만든 상태는 ReservationAttemptNoReuseIntegrationTest에서 검증한다)
 * - 공용 RepositoryTestSupport를 상속해 다른 Repository 테스트와 같은 Spring 컨텍스트를 쓰고, 테스트가 끝나면 롤백된다
 */
class ReferralRescheduleCountRepositoryTest extends RepositoryTestSupport {

    private static final LocalDateTime EXAM_AT = LocalDateTime.of(2026, 12, 1, 10, 0);

    @Autowired
    private ReferralRepository referralRepository;

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private EntityManager em;

    private final UUID institutionA = UUID.randomUUID();
    private final UUID institutionB = UUID.randomUUID();

    private long count(UUID institutionId) {
        return referralRepository.countNeedsRescheduleByLatestBlockedInstitution(institutionId);
    }

    @Test
    @DisplayName("T12. 대상 의뢰가 없는 기관은 0")
    void zeroWhenNoTarget() {
        assertThat(count(institutionA)).isZero();
        assertThat(count(UUID.randomUUID())).isZero();
    }

    @Test
    @DisplayName("T11. 서로 다른 의뢰·기관이 섞여도 기관별로 정확히 센다")
    void mixedReferralsAndInstitutions() {
        // A에서 차단된 의뢰 2건
        reservation(referral(ReferralStatus.NEEDS_RESCHEDULE), 1, institutionA, ReservationStatus.BLOCKED);
        reservation(referral(ReferralStatus.NEEDS_RESCHEDULE), 1, institutionA, ReservationStatus.BLOCKED);
        // B에서 차단된 의뢰 1건
        reservation(referral(ReferralStatus.NEEDS_RESCHEDULE), 1, institutionB, ReservationStatus.BLOCKED);
        // A에서 차단된 뒤 B로 재예약했다가 B에서도 차단된 의뢰 1건 → B
        Referral moved = referral(ReferralStatus.NEEDS_RESCHEDULE);
        reservation(moved, 1, institutionA, ReservationStatus.BLOCKED);
        reservation(moved, 2, institutionB, ReservationStatus.BLOCKED);

        assertThat(count(institutionA)).isEqualTo(2);
        assertThat(count(institutionB)).isEqualTo(2);
    }

    @Test
    @DisplayName("T10. NEEDS_RESCHEDULE가 아닌 의뢰(FAILED·CANCELED·CONFIRMED·PENDING)는 BLOCKED 예약이 있어도 제외한다")
    void excludesOtherReferralStatuses() {
        for (ReferralStatus status : new ReferralStatus[]{ReferralStatus.FAILED, ReferralStatus.CANCELED,
                ReferralStatus.CONFIRMED, ReferralStatus.PENDING}) {
            reservation(referral(status), 1, institutionA, ReservationStatus.BLOCKED);
        }

        assertThat(count(institutionA)).isZero();
    }

    @Test
    @DisplayName("재예약 실패로 현재 예약이 FAILED여도 가장 최근 차단 예약의 기관으로 센다")
    void latestFailedRetryKeepsBlockedInstitution() {
        Referral referral = referral(ReferralStatus.NEEDS_RESCHEDULE);
        reservation(referral, 1, institutionA, ReservationStatus.BLOCKED);
        reservation(referral, 2, institutionB, ReservationStatus.FAILED);   // B로 재예약을 시도했다가 실패
        reservation(referral, 3, institutionB, ReservationStatus.FAILED);

        assertThat(count(institutionA)).isEqualTo(1);
        assertThat(count(institutionB)).isZero();
    }

    @Test
    @DisplayName("T8. 차단된 예약이 소프트 삭제됐어도 원래 기관으로 센다")
    void softDeletedBlockedReservationIsIncluded() {
        Referral referral = referral(ReferralStatus.NEEDS_RESCHEDULE);
        Reservation blocked = reservation(referral, 1, institutionA, ReservationStatus.BLOCKED);
        softDelete(blocked);
        assertThat(reservationRepository.findById(blocked.getId())).isEmpty();   // JPA 조회에서는 보이지 않음

        assertThat(count(institutionA)).isEqualTo(1);
    }

    @Test
    @DisplayName("소프트 삭제된 의뢰는 제외한다")
    void deletedReferralIsExcluded() {
        Referral referral = referral(ReferralStatus.NEEDS_RESCHEDULE);
        reservation(referral, 1, institutionA, ReservationStatus.BLOCKED);
        em.createNativeQuery("update p_referrals set deleted_at = :now where id = :id")
                .setParameter("now", LocalDateTime.now()).setParameter("id", referral.getId()).executeUpdate();

        assertThat(count(institutionA)).isZero();
    }

    @Test
    @DisplayName("레거시: 같은 attemptNo가 두 번 쓰였으면 나중에 만들어진 차단 예약의 기관으로 센다")
    void legacyDuplicateAttemptNoUsesLaterCreated() {
        // 과거 결함: 1번 예약(A, BLOCKED)을 삭제한 뒤 재예약이 다시 1번을 받았고(B), 그 예약도 차단됐다
        Referral referral = referral(ReferralStatus.NEEDS_RESCHEDULE);
        Reservation older = reservation(referral, 1, institutionA, ReservationStatus.BLOCKED);
        Reservation newer = reservation(referral, 1, institutionB, ReservationStatus.BLOCKED);
        createdAt(older, EXAM_AT.minusDays(10));
        softDelete(older);
        createdAt(newer, EXAM_AT.minusDays(9));

        assertThat(count(institutionB)).isEqualTo(1);
        assertThat(count(institutionA)).isZero();

        // 나중 예약도 과거 정책으로 삭제됐어도 순서는 같다
        softDelete(newer);
        assertThat(count(institutionB)).isEqualTo(1);
        assertThat(count(institutionA)).isZero();
    }

    @Test
    @DisplayName("레거시: 같은 attemptNo에서 created_at이 없는 행은 순서에서 뒤로 보낸다 (기록이 있는 쪽을 최근으로 봄)")
    void legacyDuplicateAttemptNoWithNullCreatedAt() {
        Referral referral = referral(ReferralStatus.NEEDS_RESCHEDULE);
        Reservation unknown = reservation(referral, 1, institutionA, ReservationStatus.BLOCKED);
        reservation(referral, 1, institutionB, ReservationStatus.BLOCKED);
        createdAt(unknown, null);

        assertThat(count(institutionB)).isEqualTo(1);
        assertThat(count(institutionA)).isZero();
    }

    @Test
    @DisplayName("한계: BLOCKED 예약이 하나도 없는 NEEDS_RESCHEDULE 의뢰는 어느 기관에도 집계되지 않는다")
    void needsRescheduleWithoutBlockedIsNotCounted() {
        Referral referral = referral(ReferralStatus.NEEDS_RESCHEDULE);
        reservation(referral, 1, institutionA, ReservationStatus.FAILED);

        assertThat(count(institutionA)).isZero();
    }

    // 상태 전이 메서드를 거쳐 원하는 의뢰 상태로 저장한다
    private Referral referral(ReferralStatus status) {
        Referral referral = Referral.create(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "가상환자",
                UUID.randomUUID());
        switch (status) {
            case PENDING -> {
            }
            case CONFIRMED -> referral.confirm();
            case NEEDS_RESCHEDULE -> {
                referral.confirm();
                referral.requestReschedule();
            }
            case FAILED -> referral.fail();
            case CANCELED -> {
                referral.confirm();
                referral.cancel();
            }
            default -> throw new IllegalArgumentException("테스트에서 쓰지 않는 상태: " + status);
        }
        return referralRepository.saveAndFlush(referral);
    }

    // 상태 전이 메서드를 거쳐 원하는 예약 상태로 저장한다 (검사기관 스냅샷과 시도 번호를 지정)
    private Reservation reservation(Referral referral, int attemptNo, UUID examInstitutionId, ReservationStatus status) {
        Reservation reservation = Reservation.create(referral, attemptNo, UUID.randomUUID(), examInstitutionId,
                UUID.randomUUID(), EXAM_AT, EXAM_AT, EXAM_AT.plusHours(1));
        switch (status) {
            case REQUESTED -> {
            }
            case FAILED -> reservation.fail(FailureReason.SLOT_CONFLICT);
            case BLOCKED -> {
                reservation.hold();
                reservation.confirm();
                reservation.block();
            }
            default -> throw new IllegalArgumentException("테스트에서 쓰지 않는 상태: " + status);
        }
        return reservationRepository.saveAndFlush(reservation);
    }

    // 과거 정책으로 이미 삭제된 예약 (현재는 예약 삭제 API가 없음)
    private void softDelete(Reservation reservation) {
        em.createNativeQuery("update p_reservations set deleted_at = :now where id = :id")
                .setParameter("now", LocalDateTime.now()).setParameter("id", reservation.getId()).executeUpdate();
        em.clear();   // 영속성 컨텍스트에 남은 삭제 전 엔티티 대신 DB를 다시 읽게 한다
    }

    private void createdAt(Reservation reservation, LocalDateTime createdAt) {
        if (createdAt == null) {   // 타입 없는 null 바인딩을 피하려고 SQL에 직접 쓴다
            em.createNativeQuery("update p_reservations set created_at = null where id = :id")
                    .setParameter("id", reservation.getId()).executeUpdate();
            return;
        }
        em.createNativeQuery("update p_reservations set created_at = :createdAt where id = :id")
                .setParameter("createdAt", createdAt).setParameter("id", reservation.getId()).executeUpdate();
    }
}
