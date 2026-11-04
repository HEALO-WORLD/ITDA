package healoworld.referral_service.support;

import healoworld.referral_service.domain.entity.Referral;
import healoworld.referral_service.domain.entity.Reservation;
import healoworld.referral_service.domain.enums.FailureReason;
import healoworld.referral_service.domain.enums.ReferralStatus;
import healoworld.referral_service.domain.enums.ReservationStatus;
import healoworld.referral_service.infrastructure.repository.ReferralRepository;
import healoworld.referral_service.infrastructure.repository.ReservationRepository;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;

// mock Repository의 조건부 UPDATE를 DB처럼 흉내 낸다.
// 등록한 엔티티가 삭제되지 않았고 현재 상태가 쿼리의 기대 상태와 같을 때만 필드를 바꾸고 1을, 아니면 0을 돌려준다.
// 기대·목표 상태는 실제 쿼리(JPQL)에 고정된 값과 똑같이 맞춘다.
public final class ConditionalUpdateStubs {
    private static final List<ReservationStatus> ACTIVE_RESERVATION =
            List.of(ReservationStatus.REQUESTED, ReservationStatus.HELD, ReservationStatus.CONFIRMED);
    private static final List<ReferralStatus> CANCELABLE_REFERRAL =
            List.of(ReferralStatus.CONFIRMED, ReferralStatus.NEEDS_RESCHEDULE);

    private final Map<UUID, Reservation> reservations = new HashMap<>();
    private final Map<UUID, Referral> referrals = new HashMap<>();

    public ConditionalUpdateStubs(ReservationRepository reservationRepository, ReferralRepository referralRepository) {
        stubReservation(reservationRepository);
        stubReferral(referralRepository);
    }

    public void register(Reservation reservation) {
        reservations.put(reservation.getId(), reservation);
        register(reservation.getReferral());
    }

    public void register(Referral referral) {
        referrals.put(referral.getId(), referral);
    }

    private void stubReservation(ReservationRepository repository) {
        // (reservationId, now)
        lenient().when(repository.holdIfRequested(any(), any())).thenAnswer(inv -> reservation(
                inv.getArgument(0), List.of(ReservationStatus.REQUESTED),
                r -> status(r, ReservationStatus.HELD)));

        // (reservationId, now)
        lenient().when(repository.confirmIfHeld(any(), any())).thenAnswer(inv -> reservation(
                inv.getArgument(0), List.of(ReservationStatus.HELD), r -> {
                    status(r, ReservationStatus.CONFIRMED);
                    ReflectionTestUtils.setField(r, "confirmedAt", inv.<LocalDateTime>getArgument(1));
                }));

        // (reservationId, now, failureReason, expected)
        lenient().when(repository.failIf(any(), any(), any(), any())).thenAnswer(inv -> reservation(
                inv.getArgument(0), List.of(inv.<ReservationStatus>getArgument(3)),
                r -> end(r, ReservationStatus.FAILED, inv.getArgument(2), inv.getArgument(1))));

        // (reservationId, now)
        lenient().when(repository.blockIfActive(any(), any())).thenAnswer(inv -> reservation(
                inv.getArgument(0), ACTIVE_RESERVATION,
                r -> end(r, ReservationStatus.BLOCKED, FailureReason.EQUIPMENT_FAILURE, inv.getArgument(1))));

        // (reservationId, userId, now)
        lenient().when(repository.cancelIfConfirmed(any(), any(), any())).thenAnswer(inv -> reservation(
                inv.getArgument(0), List.of(ReservationStatus.CONFIRMED), r -> {
                    status(r, ReservationStatus.CANCELED);
                    ReflectionTestUtils.setField(r, "endedAt", inv.<LocalDateTime>getArgument(2));
                    ReflectionTestUtils.setField(r, "updatedBy", inv.<UUID>getArgument(1));
                }));

        // (reservationId, userId, now, allowed)
        lenient().when(repository.softDeleteIf(any(), any(), any(), any())).thenAnswer(inv -> reservation(
                inv.getArgument(0), inv.<Collection<ReservationStatus>>getArgument(3),
                r -> softDeleted(r, inv.getArgument(1), inv.getArgument(2))));
    }

    private void stubReferral(ReferralRepository repository) {
        // (referralId, now)
        lenient().when(repository.confirmIfPending(any(), any())).thenAnswer(inv -> referral(
                inv.getArgument(0), List.of(ReferralStatus.PENDING),
                r -> status(r, ReferralStatus.CONFIRMED)));

        // (referralId, now)
        lenient().when(repository.failIfPending(any(), any())).thenAnswer(inv -> referral(
                inv.getArgument(0), List.of(ReferralStatus.PENDING),
                r -> status(r, ReferralStatus.FAILED)));

        // (referralId, now, allowed)
        lenient().when(repository.requestRescheduleIfIn(any(), any(), any())).thenAnswer(inv -> referral(
                inv.getArgument(0), inv.<Collection<ReferralStatus>>getArgument(2),
                r -> status(r, ReferralStatus.NEEDS_RESCHEDULE)));

        // (referralId, userId, now)
        lenient().when(repository.cancelIfCancelable(any(), any(), any())).thenAnswer(inv -> referral(
                inv.getArgument(0), CANCELABLE_REFERRAL, r -> {
                    status(r, ReferralStatus.CANCELED);
                    ReflectionTestUtils.setField(r, "updatedBy", inv.<UUID>getArgument(1));
                }));

        // (referralId, newReservationId, userId, now)
        lenient().when(repository.rescheduleIfNeedsReschedule(any(), any(), any(), any())).thenAnswer(inv -> referral(
                inv.getArgument(0), List.of(ReferralStatus.NEEDS_RESCHEDULE), r -> {
                    status(r, ReferralStatus.PENDING);
                    ReflectionTestUtils.setField(r, "currentReservationId", inv.<UUID>getArgument(1));
                    ReflectionTestUtils.setField(r, "updatedBy", inv.<UUID>getArgument(2));
                }));

        // (referralId, userId, now, allowed)
        lenient().when(repository.softDeleteIf(any(), any(), any(), any())).thenAnswer(inv -> referral(
                inv.getArgument(0), inv.<Collection<ReferralStatus>>getArgument(3),
                r -> softDeleted(r, inv.getArgument(1), inv.getArgument(2))));
    }

    private int reservation(UUID id, Collection<ReservationStatus> expected, Consumer<Reservation> apply) {
        Reservation reservation = reservations.get(id);
        if (reservation == null || reservation.getDeletedAt() != null || !expected.contains(reservation.getReservationStatus())) {
            return 0;
        }
        apply.accept(reservation);
        return 1;
    }

    private int referral(UUID id, Collection<ReferralStatus> expected, Consumer<Referral> apply) {
        Referral referral = referrals.get(id);
        if (referral == null || referral.getDeletedAt() != null || !expected.contains(referral.getReferralStatus())) {
            return 0;
        }
        apply.accept(referral);
        return 1;
    }

    private static void status(Reservation reservation, ReservationStatus to) {
        ReflectionTestUtils.setField(reservation, "reservationStatus", to);
    }

    private static void status(Referral referral, ReferralStatus to) {
        ReflectionTestUtils.setField(referral, "referralStatus", to);
    }

    private static void end(Reservation reservation, ReservationStatus to, FailureReason reason, LocalDateTime now) {
        status(reservation, to);
        ReflectionTestUtils.setField(reservation, "failureReason", reason);
        ReflectionTestUtils.setField(reservation, "endedAt", now);
    }

    private static void softDeleted(Object entity, UUID userId, LocalDateTime now) {
        ReflectionTestUtils.setField(entity, "deletedAt", now);
        ReflectionTestUtils.setField(entity, "deletedBy", userId);
        ReflectionTestUtils.setField(entity, "updatedBy", userId);
    }
}
