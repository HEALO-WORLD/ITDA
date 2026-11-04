package healoworld.referral_service.infrastructure.repository;

import healoworld.referral_service.domain.entity.Referral;
import healoworld.referral_service.domain.entity.Reservation;
import healoworld.referral_service.domain.enums.FailureReason;
import healoworld.referral_service.domain.enums.ReferralStatus;
import healoworld.referral_service.domain.enums.ReservationStatus;
import healoworld.referral_service.support.RepositoryTestSupport;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// 의뢰·예약 상태 전이 조건부 UPDATE를 실제 DB로 검증한다.
// 파라미터 이름·바인딩 실수나 WHERE 조건 오류는 mock 단위 테스트로는 드러나지 않는다.
class ConditionalUpdateRepositoryTest extends RepositoryTestSupport {
    private static final LocalDateTime T0 = LocalDateTime.of(2026, 12, 1, 10, 0);
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 4, 12, 0, 0);
    private static final UUID USER_ID = UUID.randomUUID();

    @Autowired
    private ReferralRepository referralRepository;

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private EntityManager em;

    @Nested
    @DisplayName("ReservationRepository")
    class ReservationUpdates {

        @Test
        @DisplayName("holdIfRequested: REQUESTED면 HELD로 바꾸고 1, 다시 하면 0")
        void holdIfRequested() {
            Reservation reservation = reservation(ReservationStatus.REQUESTED);

            assertThat(reservationRepository.holdIfRequested(reservation.getId(), NOW)).isEqualTo(1);
            assertThat(reload(reservation).getReservationStatus()).isEqualTo(ReservationStatus.HELD);
            assertThat(reservationRepository.holdIfRequested(reservation.getId(), NOW)).isZero();
        }

        @Test
        @DisplayName("confirmIfHeld: HELD면 CONFIRMED와 확정 시각을 기록하고 1, REQUESTED면 0")
        void confirmIfHeld() {
            Reservation held = reservation(ReservationStatus.HELD);
            Reservation requested = reservation(ReservationStatus.REQUESTED);

            assertThat(reservationRepository.confirmIfHeld(held.getId(), NOW)).isEqualTo(1);
            assertThat(reservationRepository.confirmIfHeld(requested.getId(), NOW)).isZero();

            Reservation confirmed = reload(held);
            assertThat(confirmed.getReservationStatus()).isEqualTo(ReservationStatus.CONFIRMED);
            assertThat(confirmed.getConfirmedAt()).isEqualTo(NOW);
            assertThat(reload(requested).getReservationStatus()).isEqualTo(ReservationStatus.REQUESTED);
        }

        @Test
        @DisplayName("failIf: 기대 상태면 FAILED·실패 사유·종료 시각을 기록하고 1, 기대 상태가 다르면 0")
        void failIf() {
            Reservation held = reservation(ReservationStatus.HELD);

            assertThat(reservationRepository.failIf(held.getId(), NOW, FailureReason.SLOT_CONFLICT, ReservationStatus.REQUESTED)).isZero();
            assertThat(reservationRepository.failIf(held.getId(), NOW, FailureReason.CONFIRM_FAILED, ReservationStatus.HELD)).isEqualTo(1);

            Reservation failed = reload(held);
            assertThat(failed.getReservationStatus()).isEqualTo(ReservationStatus.FAILED);
            assertThat(failed.getFailureReason()).isEqualTo(FailureReason.CONFIRM_FAILED);
            assertThat(failed.getEndedAt()).isEqualTo(NOW);
        }

        @Test
        @DisplayName("blockIfActive: 진행 중(REQUESTED·HELD·CONFIRMED)이면 BLOCKED(EQUIPMENT_FAILURE)로 바꾸고 1, 목록 밖이면 0")
        void blockIfActive() {
            Reservation confirmed = reservation(ReservationStatus.CONFIRMED);
            Reservation failed = reservation(ReservationStatus.FAILED);

            assertThat(reservationRepository.blockIfActive(confirmed.getId(), NOW)).isEqualTo(1);
            assertThat(reservationRepository.blockIfActive(failed.getId(), NOW)).isZero();

            Reservation blocked = reload(confirmed);
            assertThat(blocked.getReservationStatus()).isEqualTo(ReservationStatus.BLOCKED);
            assertThat(blocked.getFailureReason()).isEqualTo(FailureReason.EQUIPMENT_FAILURE);
            assertThat(blocked.getEndedAt()).isEqualTo(NOW);
            assertThat(reload(failed).getReservationStatus()).isEqualTo(ReservationStatus.FAILED);
        }

        @Test
        @DisplayName("cancelIfConfirmed: CONFIRMED면 CANCELED·종료 시각·변경자를 기록하고 확정 시각은 그대로, REQUESTED면 0")
        void cancelIfConfirmed() {
            Reservation confirmed = reservation(ReservationStatus.CONFIRMED);
            LocalDateTime confirmedAt = reload(confirmed).getConfirmedAt();
            Reservation requested = reservation(ReservationStatus.REQUESTED);

            assertThat(reservationRepository.cancelIfConfirmed(confirmed.getId(), USER_ID, NOW)).isEqualTo(1);
            assertThat(reservationRepository.cancelIfConfirmed(requested.getId(), USER_ID, NOW)).isZero();

            Reservation canceled = reload(confirmed);
            assertThat(canceled.getReservationStatus()).isEqualTo(ReservationStatus.CANCELED);
            assertThat(canceled.getEndedAt()).isEqualTo(NOW);
            assertThat(canceled.getConfirmedAt()).isEqualTo(confirmedAt);
            assertThat(canceled.getUpdatedBy()).isEqualTo(USER_ID);
        }

        @Test
        @DisplayName("softDeleteIf: 허용 상태면 삭제 시각·삭제자를 기록해 조회에서 빠지고 1, 진행 중이면 0")
        void softDeleteIf() {
            List<ReservationStatus> allowed = List.of(ReservationStatus.FAILED, ReservationStatus.CANCELED, ReservationStatus.BLOCKED);
            Reservation failed = reservation(ReservationStatus.FAILED);
            Reservation confirmed = reservation(ReservationStatus.CONFIRMED);

            assertThat(reservationRepository.softDeleteIf(failed.getId(), USER_ID, NOW, allowed)).isEqualTo(1);
            assertThat(reservationRepository.softDeleteIf(confirmed.getId(), USER_ID, NOW, allowed)).isZero();
            assertThat(reservationRepository.softDeleteIf(failed.getId(), USER_ID, NOW, allowed)).isZero();   // 이미 삭제됨

            em.clear();
            assertThat(reservationRepository.findById(failed.getId())).isEmpty();
            assertDeleted("p_reservations", failed.getId());
            assertThat(reload(confirmed).getDeletedAt()).isNull();
        }

        @Test
        @DisplayName("soft delete된 예약은 상태가 맞아도 바꾸지 않는다")
        void skipsDeleted() {
            Reservation reservation = reservation(ReservationStatus.REQUESTED);
            em.createQuery("update Reservation r set r.deletedAt = :now where r.id = :id")
                    .setParameter("now", NOW).setParameter("id", reservation.getId()).executeUpdate();

            assertThat(reservationRepository.holdIfRequested(reservation.getId(), NOW)).isZero();
        }
    }

    @Nested
    @DisplayName("ReferralRepository")
    class ReferralUpdates {

        @Test
        @DisplayName("confirmIfPending: PENDING이면 CONFIRMED로 바꾸고 1, 다시 하면 0")
        void confirmIfPending() {
            Referral referral = referral(ReferralStatus.PENDING);

            assertThat(referralRepository.confirmIfPending(referral.getId(), NOW)).isEqualTo(1);
            assertThat(reload(referral).getReferralStatus()).isEqualTo(ReferralStatus.CONFIRMED);
            assertThat(referralRepository.confirmIfPending(referral.getId(), NOW)).isZero();
        }

        @Test
        @DisplayName("failIfPending: PENDING이면 FAILED로 바꾸고 1, CONFIRMED면 0")
        void failIfPending() {
            Referral pending = referral(ReferralStatus.PENDING);
            Referral confirmed = referral(ReferralStatus.CONFIRMED);

            assertThat(referralRepository.failIfPending(pending.getId(), NOW)).isEqualTo(1);
            assertThat(referralRepository.failIfPending(confirmed.getId(), NOW)).isZero();
            assertThat(reload(pending).getReferralStatus()).isEqualTo(ReferralStatus.FAILED);
            assertThat(reload(confirmed).getReferralStatus()).isEqualTo(ReferralStatus.CONFIRMED);
        }

        @Test
        @DisplayName("requestRescheduleIfIn: 허용 상태면 NEEDS_RESCHEDULE로 바꾸고 1, 목록 밖이면 0")
        void requestRescheduleIfIn() {
            List<ReferralStatus> allowed = List.of(ReferralStatus.PENDING, ReferralStatus.CONFIRMED);
            Referral confirmed = referral(ReferralStatus.CONFIRMED);
            Referral failed = referral(ReferralStatus.FAILED);

            assertThat(referralRepository.requestRescheduleIfIn(confirmed.getId(), NOW, allowed)).isEqualTo(1);
            assertThat(referralRepository.requestRescheduleIfIn(failed.getId(), NOW, allowed)).isZero();
            assertThat(reload(confirmed).getReferralStatus()).isEqualTo(ReferralStatus.NEEDS_RESCHEDULE);
        }

        @Test
        @DisplayName("cancelIfCancelable: CONFIRMED·NEEDS_RESCHEDULE면 CANCELED로 바꾸고 1, PENDING이면 0")
        void cancelIfCancelable() {
            Referral needsReschedule = referral(ReferralStatus.NEEDS_RESCHEDULE);
            Referral pending = referral(ReferralStatus.PENDING);

            assertThat(referralRepository.cancelIfCancelable(needsReschedule.getId(), USER_ID, NOW)).isEqualTo(1);
            assertThat(referralRepository.cancelIfCancelable(pending.getId(), USER_ID, NOW)).isZero();

            Referral canceled = reload(needsReschedule);
            assertThat(canceled.getReferralStatus()).isEqualTo(ReferralStatus.CANCELED);
            assertThat(canceled.getUpdatedBy()).isEqualTo(USER_ID);
        }

        @Test
        @DisplayName("rescheduleIfNeedsReschedule: NEEDS_RESCHEDULE면 PENDING·새 예약 연결·변경자를 기록하고 1, 다시 하면 0")
        void rescheduleIfNeedsReschedule() {
            Referral referral = referral(ReferralStatus.NEEDS_RESCHEDULE);
            UUID newReservationId = UUID.randomUUID();

            assertThat(referralRepository.rescheduleIfNeedsReschedule(referral.getId(), newReservationId, USER_ID, NOW)).isEqualTo(1);
            assertThat(referralRepository.rescheduleIfNeedsReschedule(referral.getId(), UUID.randomUUID(), USER_ID, NOW)).isZero();

            Referral rescheduled = reload(referral);
            assertThat(rescheduled.getReferralStatus()).isEqualTo(ReferralStatus.PENDING);
            assertThat(rescheduled.getCurrentReservationId()).isEqualTo(newReservationId);
            assertThat(rescheduled.getUpdatedBy()).isEqualTo(USER_ID);
        }

        @Test
        @DisplayName("softDeleteIf: FAILED·CANCELED면 삭제 시각·삭제자를 기록해 조회에서 빠지고 1, PENDING이면 0")
        void softDeleteIf() {
            List<ReferralStatus> allowed = List.of(ReferralStatus.FAILED, ReferralStatus.CANCELED);
            Referral failed = referral(ReferralStatus.FAILED);
            Referral pending = referral(ReferralStatus.PENDING);

            assertThat(referralRepository.softDeleteIf(failed.getId(), USER_ID, NOW, allowed)).isEqualTo(1);
            assertThat(referralRepository.softDeleteIf(pending.getId(), USER_ID, NOW, allowed)).isZero();
            assertThat(referralRepository.softDeleteIf(failed.getId(), USER_ID, NOW, allowed)).isZero();   // 이미 삭제됨

            em.clear();
            assertThat(referralRepository.findById(failed.getId())).isEmpty();
            assertDeleted("p_referrals", failed.getId());
            assertThat(reload(pending).getDeletedAt()).isNull();
        }
    }

    // 상태 전이 메서드를 거쳐 원하는 상태로 만든 뒤 저장한다
    private Referral referral(ReferralStatus status) {
        Referral referral = Referral.create(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "홍길동", UUID.randomUUID());
        switch (status) {
            case PENDING -> {
            }
            case CONFIRMED -> referral.confirm();
            case FAILED -> referral.fail();
            case NEEDS_RESCHEDULE -> referral.requestReschedule();
            default -> throw new IllegalArgumentException(status.name());
        }
        Referral saved = referralRepository.saveAndFlush(referral);
        em.clear();
        return saved;
    }

    private Reservation reservation(ReservationStatus status) {
        Referral referral = referralRepository.saveAndFlush(
                Referral.create(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "홍길동", UUID.randomUUID()));
        Reservation reservation = Reservation.create(referral, 1, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                T0, T0, T0.plusHours(1));
        switch (status) {
            case REQUESTED -> {
            }
            case HELD -> reservation.hold();
            case CONFIRMED -> {
                reservation.hold();
                reservation.confirm();
                // DB 정밀도(마이크로초)에 맞춰 다시 읽었을 때 비교할 수 있게 한다
                ReflectionTestUtils.setField(reservation, "confirmedAt", reservation.getConfirmedAt().truncatedTo(ChronoUnit.MICROS));
            }
            case FAILED -> reservation.fail(FailureReason.SLOT_CONFLICT);
            default -> throw new IllegalArgumentException(status.name());
        }
        Reservation saved = reservationRepository.saveAndFlush(reservation);
        em.clear();
        return saved;
    }

    // @SQLRestriction 때문에 JPA로는 삭제된 행을 읽을 수 없어 네이티브 쿼리로 확인한다
    private void assertDeleted(String table, UUID id) {
        Object[] row = (Object[]) em.createNativeQuery("select deleted_at, deleted_by from " + table + " where id = :id")
                .setParameter("id", id).getSingleResult();
        assertThat(row[0]).isNotNull();
        assertThat(row[1]).isEqualTo(USER_ID);
    }

    private Reservation reload(Reservation reservation) {
        em.clear();
        return reservationRepository.findById(reservation.getId()).orElseThrow();
    }

    private Referral reload(Referral referral) {
        em.clear();
        return referralRepository.findById(referral.getId()).orElseThrow();
    }
}
