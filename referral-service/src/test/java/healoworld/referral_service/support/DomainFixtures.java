package healoworld.referral_service.support;

import healoworld.referral_service.domain.entity.Referral;
import healoworld.referral_service.domain.entity.Reservation;
import healoworld.referral_service.domain.enums.FailureReason;
import healoworld.referral_service.domain.enums.ReferralStatus;
import healoworld.referral_service.domain.enums.ReservationStatus;
import healoworld.referral_service.presentation.dto.request.CreateReservationRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.UUID;

public final class DomainFixtures {
    public static final LocalDateTime EXAM_START = LocalDateTime.of(2026, 11, 2, 10, 0);

    private DomainFixtures() {
    }

    public static Referral referral(UUID referrerId) {
        Referral referral = Referral.create(UUID.randomUUID(), referrerId, UUID.randomUUID(), "홍길동", UUID.randomUUID());
        ReflectionTestUtils.setField(referral, "id", UUID.randomUUID());
        return referral;
    }

    // 상태 전이 메서드를 거쳐 원하는 상태의 의뢰를 만든다
    public static Referral referralIn(ReferralStatus status, UUID referrerId) {
        Referral referral = referral(referrerId);
        switch (status) {
            case PENDING -> {
            }
            case CONFIRMED -> referral.confirm();
            case FAILED -> referral.fail();
            case NEEDS_RESCHEDULE -> referral.requestReschedule();
            case CANCELED -> {
                referral.confirm();
                referral.cancel();
            }
        }
        return referral;
    }

    public static Reservation reservation(Referral referral, int attemptNo) {
        Reservation reservation = Reservation.create(referral, attemptNo, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                EXAM_START, EXAM_START, EXAM_START.plusHours(1));
        ReflectionTestUtils.setField(reservation, "id", UUID.randomUUID());
        return reservation;
    }

    // 상태 전이 메서드를 거쳐 원하는 상태의 예약을 만든다
    public static Reservation reservationIn(ReservationStatus status, Referral referral) {
        Reservation reservation = reservation(referral, 1);
        switch (status) {
            case REQUESTED -> {
            }
            case HELD -> reservation.hold();
            case CONFIRMED -> {
                reservation.hold();
                reservation.confirm();
            }
            case FAILED -> reservation.fail(FailureReason.SLOT_CONFLICT);
            case BLOCKED -> {
                reservation.hold();
                reservation.confirm();
                reservation.block();
            }
            case CANCELED -> {
                reservation.hold();
                reservation.confirm();
                reservation.cancel();
            }
        }
        return reservation;
    }

    public static CreateReservationRequest reservationRequest() {
        return CreateReservationRequest.builder()
                .slotId(UUID.randomUUID())
                .examInstitutionId(UUID.randomUUID())
                .equipmentId(UUID.randomUUID())
                .examDate(EXAM_START)
                .startTime(EXAM_START)
                .endTime(EXAM_START.plusHours(1))
                .build();
    }
}
