package healoworld.referral_service.domain.entity;

import healoworld.referral_service.domain.enums.ReferralStatus;
import healoworld.referral_service.global.exception.BaseException;
import healoworld.referral_service.global.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.UUID;

import static healoworld.referral_service.support.DomainFixtures.referralIn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.EnumSource.Mode.EXCLUDE;

class ReferralTest {
    private static final UUID USER_ID = UUID.randomUUID();

    @Test
    @DisplayName("의뢰를 생성하면 PENDING 상태이고 현재 예약이 없다")
    void create() {
        Referral referral = Referral.create(UUID.randomUUID(), USER_ID, UUID.randomUUID(), "홍길동", UUID.randomUUID());

        assertThat(referral.getReferralStatus()).isEqualTo(ReferralStatus.PENDING);
        assertThat(referral.getCurrentReservationId()).isNull();
        assertThat(referral.getReferrerId()).isEqualTo(USER_ID);
    }

    @Nested
    @DisplayName("assignReservation")
    class AssignReservation {
        @Test
        @DisplayName("PENDING 상태에서 예약을 연결한다")
        void success() {
            Referral referral = referralIn(ReferralStatus.PENDING, USER_ID);
            UUID reservationId = UUID.randomUUID();

            referral.assignReservation(reservationId);

            assertThat(referral.getCurrentReservationId()).isEqualTo(reservationId);
        }

        @Test
        @DisplayName("이미 예약이 연결되어 있으면 실패한다")
        void alreadyAssigned() {
            Referral referral = referralIn(ReferralStatus.PENDING, USER_ID);
            referral.assignReservation(UUID.randomUUID());

            assertInvalidStatus(() -> referral.assignReservation(UUID.randomUUID()));
        }

        @ParameterizedTest
        @EnumSource(value = ReferralStatus.class, names = "PENDING", mode = EXCLUDE)
        @DisplayName("PENDING이 아니면 실패한다")
        void invalidStatus(ReferralStatus status) {
            Referral referral = referralIn(status, USER_ID);

            assertInvalidStatus(() -> referral.assignReservation(UUID.randomUUID()));
        }
    }

    @Nested
    @DisplayName("confirm / fail")
    class ConfirmAndFail {
        @Test
        @DisplayName("PENDING → CONFIRMED")
        void confirm() {
            Referral referral = referralIn(ReferralStatus.PENDING, USER_ID);

            referral.confirm();

            assertThat(referral.getReferralStatus()).isEqualTo(ReferralStatus.CONFIRMED);
        }

        @Test
        @DisplayName("PENDING → FAILED")
        void fail() {
            Referral referral = referralIn(ReferralStatus.PENDING, USER_ID);

            referral.fail();

            assertThat(referral.getReferralStatus()).isEqualTo(ReferralStatus.FAILED);
        }

        @ParameterizedTest
        @EnumSource(value = ReferralStatus.class, names = "PENDING", mode = EXCLUDE)
        @DisplayName("PENDING이 아니면 confirm할 수 없다")
        void confirmInvalidStatus(ReferralStatus status) {
            Referral referral = referralIn(status, USER_ID);

            assertInvalidStatus(referral::confirm);
        }

        @ParameterizedTest
        @EnumSource(value = ReferralStatus.class, names = "PENDING", mode = EXCLUDE)
        @DisplayName("PENDING이 아니면 fail할 수 없다")
        void failInvalidStatus(ReferralStatus status) {
            Referral referral = referralIn(status, USER_ID);

            assertInvalidStatus(referral::fail);
        }
    }

    @Nested
    @DisplayName("requestReschedule / reschedule")
    class Reschedule {
        @ParameterizedTest
        @EnumSource(value = ReferralStatus.class, names = {"PENDING", "CONFIRMED"})
        @DisplayName("PENDING, CONFIRMED → NEEDS_RESCHEDULE")
        void requestReschedule(ReferralStatus status) {
            Referral referral = referralIn(status, USER_ID);

            referral.requestReschedule();

            assertThat(referral.getReferralStatus()).isEqualTo(ReferralStatus.NEEDS_RESCHEDULE);
        }

        @ParameterizedTest
        @EnumSource(value = ReferralStatus.class, names = {"PENDING", "CONFIRMED"}, mode = EXCLUDE)
        @DisplayName("PENDING, CONFIRMED가 아니면 재조정을 요청할 수 없다")
        void requestRescheduleInvalidStatus(ReferralStatus status) {
            Referral referral = referralIn(status, USER_ID);

            assertInvalidStatus(referral::requestReschedule);
        }

        @Test
        @DisplayName("NEEDS_RESCHEDULE → PENDING, 현재 예약이 새 예약으로 바뀐다")
        void reschedule() {
            Referral referral = referralIn(ReferralStatus.PENDING, USER_ID);
            referral.assignReservation(UUID.randomUUID());
            referral.requestReschedule();
            UUID newReservationId = UUID.randomUUID();

            referral.reschedule(newReservationId);

            assertThat(referral.getReferralStatus()).isEqualTo(ReferralStatus.PENDING);
            assertThat(referral.getCurrentReservationId()).isEqualTo(newReservationId);
        }

        @ParameterizedTest
        @EnumSource(value = ReferralStatus.class, names = "NEEDS_RESCHEDULE", mode = EXCLUDE)
        @DisplayName("NEEDS_RESCHEDULE가 아니면 재예약할 수 없다")
        void rescheduleInvalidStatus(ReferralStatus status) {
            Referral referral = referralIn(status, USER_ID);

            assertInvalidStatus(() -> referral.reschedule(UUID.randomUUID()));
        }
    }

    @Nested
    @DisplayName("cancel")
    class Cancel {
        @ParameterizedTest
        @EnumSource(value = ReferralStatus.class, names = {"CONFIRMED", "NEEDS_RESCHEDULE"})
        @DisplayName("CONFIRMED, NEEDS_RESCHEDULE → CANCELED")
        void success(ReferralStatus status) {
            Referral referral = referralIn(status, USER_ID);

            referral.cancel();

            assertThat(referral.getReferralStatus()).isEqualTo(ReferralStatus.CANCELED);
        }

        @ParameterizedTest
        @EnumSource(value = ReferralStatus.class, names = {"CONFIRMED", "NEEDS_RESCHEDULE"}, mode = EXCLUDE)
        @DisplayName("CONFIRMED, NEEDS_RESCHEDULE가 아니면 취소할 수 없다")
        void invalidStatus(ReferralStatus status) {
            Referral referral = referralIn(status, USER_ID);

            assertInvalidStatus(referral::cancel);
        }
    }

    @Nested
    @DisplayName("delete")
    class Delete {
        @ParameterizedTest
        @EnumSource(value = ReferralStatus.class, names = {"FAILED", "CANCELED"})
        @DisplayName("FAILED, CANCELED 상태면 soft delete된다")
        void success(ReferralStatus status) {
            Referral referral = referralIn(status, USER_ID);

            referral.delete(USER_ID);

            assertThat(referral.getDeletedAt()).isNotNull();
            assertThat(referral.getDeletedBy()).isEqualTo(USER_ID);
        }

        @ParameterizedTest
        @EnumSource(value = ReferralStatus.class, names = {"FAILED", "CANCELED"}, mode = EXCLUDE)
        @DisplayName("진행 중인 의뢰는 삭제할 수 없다")
        void invalidStatus(ReferralStatus status) {
            Referral referral = referralIn(status, USER_ID);

            assertInvalidStatus(() -> referral.delete(USER_ID));
            assertThat(referral.getDeletedAt()).isNull();
        }
    }

    @Test
    @DisplayName("isReferredBy는 의뢰자 본인일 때만 true")
    void isReferredBy() {
        Referral referral = referralIn(ReferralStatus.PENDING, USER_ID);

        assertThat(referral.isReferredBy(USER_ID)).isTrue();
        assertThat(referral.isReferredBy(UUID.randomUUID())).isFalse();
    }

    private void assertInvalidStatus(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOf(BaseException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INVALID_REFERRAL_STATUS);
    }
}
