package healoworld.referral_service.domain.entity;

import healoworld.referral_service.domain.enums.FailureReason;
import healoworld.referral_service.domain.enums.ReferralStatus;
import healoworld.referral_service.domain.enums.ReservationStatus;
import healoworld.referral_service.global.exception.BaseException;
import healoworld.referral_service.global.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.UUID;

import static healoworld.referral_service.support.DomainFixtures.referralIn;
import static healoworld.referral_service.support.DomainFixtures.reservation;
import static healoworld.referral_service.support.DomainFixtures.reservationIn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.EnumSource.Mode.EXCLUDE;

class ReservationTest {
    private static final UUID USER_ID = UUID.randomUUID();

    private Referral referral;

    @BeforeEach
    void setUp() {
        referral = referralIn(ReferralStatus.PENDING, USER_ID);
    }

    @Test
    @DisplayName("예약을 생성하면 REQUESTED 상태이다")
    void create() {
        Reservation reservation = reservation(referral, 1);

        assertThat(reservation.getReservationStatus()).isEqualTo(ReservationStatus.REQUESTED);
        assertThat(reservation.getReferral()).isSameAs(referral);
        assertThat(reservation.getAttemptNo()).isEqualTo(1);
        assertThat(reservation.getFailureReason()).isNull();
    }

    @Nested
    @DisplayName("hold / confirm")
    class HoldAndConfirm {
        @Test
        @DisplayName("REQUESTED → HELD")
        void hold() {
            Reservation reservation = reservationIn(ReservationStatus.REQUESTED, referral);

            reservation.hold();

            assertThat(reservation.getReservationStatus()).isEqualTo(ReservationStatus.HELD);
        }

        @ParameterizedTest
        @EnumSource(value = ReservationStatus.class, names = "REQUESTED", mode = EXCLUDE)
        @DisplayName("REQUESTED가 아니면 hold할 수 없다")
        void holdInvalidStatus(ReservationStatus status) {
            Reservation reservation = reservationIn(status, referral);

            assertInvalidStatus(reservation::hold);
        }

        @Test
        @DisplayName("HELD → CONFIRMED, 확정 시각이 기록된다")
        void confirm() {
            Reservation reservation = reservationIn(ReservationStatus.HELD, referral);

            reservation.confirm();

            assertThat(reservation.getReservationStatus()).isEqualTo(ReservationStatus.CONFIRMED);
            assertThat(reservation.getConfirmedAt()).isNotNull();
        }

        @ParameterizedTest
        @EnumSource(value = ReservationStatus.class, names = "HELD", mode = EXCLUDE)
        @DisplayName("HELD가 아니면 confirm할 수 없다")
        void confirmInvalidStatus(ReservationStatus status) {
            Reservation reservation = reservationIn(status, referral);

            assertInvalidStatus(reservation::confirm);
        }
    }

    @Nested
    @DisplayName("fail")
    class Fail {
        @ParameterizedTest(name = "{0} 상태에서 {1}")
        @CsvSource({
                "REQUESTED, SLOT_CONFLICT",
                "REQUESTED, EQUIPMENT_FAILURE",
                "HELD, CONFIRM_FAILED",
                "HELD, HOLD_EXPIRED",
                "HELD, EQUIPMENT_FAILURE"
        })
        @DisplayName("상태별로 허용된 사유로 실패 처리된다")
        void success(ReservationStatus status, FailureReason reason) {
            Reservation reservation = reservationIn(status, referral);

            reservation.fail(reason);

            assertThat(reservation.getReservationStatus()).isEqualTo(ReservationStatus.FAILED);
            assertThat(reservation.getFailureReason()).isEqualTo(reason);
            assertThat(reservation.getEndedAt()).isNotNull();
        }

        @ParameterizedTest(name = "{0} 상태에서 {1}")
        @CsvSource({
                "REQUESTED, CONFIRM_FAILED",
                "REQUESTED, HOLD_EXPIRED",
                "HELD, SLOT_CONFLICT"
        })
        @DisplayName("상태에 맞지 않는 사유면 실패한다")
        void invalidReason(ReservationStatus status, FailureReason reason) {
            Reservation reservation = reservationIn(status, referral);

            assertThatThrownBy(() -> reservation.fail(reason))
                    .isInstanceOf(BaseException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.INVALID_FAILURE_REASON);
            assertThat(reservation.getReservationStatus()).isEqualTo(status);
        }

        @ParameterizedTest
        @EnumSource(value = ReservationStatus.class, names = {"REQUESTED", "HELD"}, mode = EXCLUDE)
        @DisplayName("REQUESTED, HELD가 아니면 실패 처리할 수 없다")
        void invalidStatus(ReservationStatus status) {
            Reservation reservation = reservationIn(status, referral);

            assertInvalidStatus(() -> reservation.fail(FailureReason.EQUIPMENT_FAILURE));
        }
    }

    @Nested
    @DisplayName("block / cancel")
    class BlockAndCancel {
        @Test
        @DisplayName("CONFIRMED → BLOCKED, 사유는 EQUIPMENT_FAILURE")
        void block() {
            Reservation reservation = reservationIn(ReservationStatus.CONFIRMED, referral);

            reservation.block();

            assertThat(reservation.getReservationStatus()).isEqualTo(ReservationStatus.BLOCKED);
            assertThat(reservation.getFailureReason()).isEqualTo(FailureReason.EQUIPMENT_FAILURE);
            assertThat(reservation.getEndedAt()).isNotNull();
        }

        @Test
        @DisplayName("CONFIRMED → CANCELED")
        void cancel() {
            Reservation reservation = reservationIn(ReservationStatus.CONFIRMED, referral);

            reservation.cancel();

            assertThat(reservation.getReservationStatus()).isEqualTo(ReservationStatus.CANCELED);
            assertThat(reservation.getEndedAt()).isNotNull();
        }

        @ParameterizedTest
        @EnumSource(value = ReservationStatus.class, names = "CONFIRMED", mode = EXCLUDE)
        @DisplayName("CONFIRMED가 아니면 block할 수 없다")
        void blockInvalidStatus(ReservationStatus status) {
            Reservation reservation = reservationIn(status, referral);

            assertInvalidStatus(reservation::block);
        }

        @ParameterizedTest
        @EnumSource(value = ReservationStatus.class, names = "CONFIRMED", mode = EXCLUDE)
        @DisplayName("CONFIRMED가 아니면 취소할 수 없다")
        void cancelInvalidStatus(ReservationStatus status) {
            Reservation reservation = reservationIn(status, referral);

            assertInvalidStatus(reservation::cancel);
        }
    }

    @Nested
    @DisplayName("delete")
    class Delete {
        @ParameterizedTest
        @EnumSource(value = ReservationStatus.class, names = {"CANCELED", "BLOCKED", "FAILED"})
        @DisplayName("종료된 예약은 soft delete된다")
        void success(ReservationStatus status) {
            Reservation reservation = reservationIn(status, referral);

            reservation.delete(USER_ID);

            assertThat(reservation.getDeletedAt()).isNotNull();
            assertThat(reservation.getDeletedBy()).isEqualTo(USER_ID);
        }

        @ParameterizedTest
        @EnumSource(value = ReservationStatus.class, names = {"CANCELED", "BLOCKED", "FAILED"}, mode = EXCLUDE)
        @DisplayName("진행 중인 예약은 삭제할 수 없다")
        void invalidStatus(ReservationStatus status) {
            Reservation reservation = reservationIn(status, referral);

            assertInvalidStatus(() -> reservation.delete(USER_ID));
            assertThat(reservation.getDeletedAt()).isNull();
        }
    }

    private void assertInvalidStatus(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOf(BaseException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INVALID_RESERVATION_STATUS);
    }
}
