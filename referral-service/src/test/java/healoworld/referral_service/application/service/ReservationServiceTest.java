package healoworld.referral_service.application.service;

import healoworld.referral_service.domain.entity.Referral;
import healoworld.referral_service.domain.entity.Reservation;
import healoworld.referral_service.domain.enums.ReferralStatus;
import healoworld.referral_service.domain.enums.ReservationStatus;
import healoworld.referral_service.domain.enums.RoleType;
import healoworld.referral_service.global.exception.BaseException;
import healoworld.referral_service.global.exception.ErrorCode;
import healoworld.referral_service.infrastructure.repository.ReservationRepository;
import healoworld.referral_service.presentation.dto.request.CreateReservationRequest;
import healoworld.referral_service.presentation.dto.response.ReservationResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static healoworld.referral_service.support.DomainFixtures.referralIn;
import static healoworld.referral_service.support.DomainFixtures.reservation;
import static healoworld.referral_service.support.DomainFixtures.reservationIn;
import static healoworld.referral_service.support.DomainFixtures.reservationRequest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ReservationServiceTest {
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID OTHER_USER_ID = UUID.randomUUID();

    @Mock
    private ReservationRepository reservationRepository;

    @InjectMocks
    private ReservationService reservationService;

    private Referral referral;

    @BeforeEach
    void setUp() {
        referral = referralIn(ReferralStatus.PENDING, USER_ID);
    }

    @Nested
    @DisplayName("createReservation")
    class CreateReservation {
        @Test
        @DisplayName("첫 예약이면 attemptNo는 1이다")
        void firstAttempt() {
            CreateReservationRequest request = reservationRequest();
            given(reservationRepository.findTopByReferralIdOrderByAttemptNoDesc(referral.getId())).willReturn(Optional.empty());

            Reservation created = reservationService.createReservation(referral, request);

            assertThat(created.getAttemptNo()).isEqualTo(1);
            assertThat(created.getReservationStatus()).isEqualTo(ReservationStatus.REQUESTED);
            assertThat(created.getReferral()).isSameAs(referral);
            assertThat(created.getSlotId()).isEqualTo(request.getSlotId());
            assertThat(created.getEquipmentId()).isEqualTo(request.getEquipmentId());
            verify(reservationRepository).save(created);
        }

        @Test
        @DisplayName("이전 예약이 있으면 마지막 attemptNo + 1")
        void nextAttempt() {
            given(reservationRepository.findTopByReferralIdOrderByAttemptNoDesc(referral.getId()))
                    .willReturn(Optional.of(reservation(referral, 2)));

            Reservation created = reservationService.createReservation(referral, reservationRequest());

            assertThat(created.getAttemptNo()).isEqualTo(3);
        }
    }

    @Nested
    @DisplayName("getReservation")
    class GetReservation {
        @Test
        @DisplayName("의뢰기관은 조회할 수 있고 referralId가 응답에 담긴다")
        void referringInstitution() {
            Reservation reservation = reservationIn(ReservationStatus.REQUESTED, referral);
            given(reservationRepository.findById(reservation.getId())).willReturn(Optional.of(reservation));

            ReservationResponse response = reservationService.getReservation(
                    referral.getReferringInstitutionId(), reservation.getId(), RoleType.REQUESTER);

            assertThat(response.getId()).isEqualTo(reservation.getId());
            assertThat(response.getReferralId()).isEqualTo(referral.getId());
        }

        @Test
        @DisplayName("검사기관은 조회할 수 있다")
        void examInstitution() {
            Reservation reservation = reservationIn(ReservationStatus.REQUESTED, referral);
            given(reservationRepository.findById(reservation.getId())).willReturn(Optional.of(reservation));

            ReservationResponse response = reservationService.getReservation(
                    reservation.getExamInstitutionId(), reservation.getId(), RoleType.EXAMINER);

            assertThat(response.getId()).isEqualTo(reservation.getId());
        }

        @Test
        @DisplayName("관리자는 관계없는 기관이어도 조회할 수 있다")
        void admin() {
            Reservation reservation = reservationIn(ReservationStatus.REQUESTED, referral);
            given(reservationRepository.findById(reservation.getId())).willReturn(Optional.of(reservation));

            ReservationResponse response = reservationService.getReservation(UUID.randomUUID(), reservation.getId(), RoleType.ADMIN);

            assertThat(response.getId()).isEqualTo(reservation.getId());
        }

        @Test
        @DisplayName("의뢰기관도 검사기관도 아니면 NOT_FOUND")
        void otherInstitution() {
            Reservation reservation = reservationIn(ReservationStatus.REQUESTED, referral);
            given(reservationRepository.findById(reservation.getId())).willReturn(Optional.of(reservation));

            assertErrorCode(() -> reservationService.getReservation(UUID.randomUUID(), reservation.getId(), RoleType.REQUESTER),
                    ErrorCode.RESERVATION_NOT_FOUND);
        }

        @Test
        @DisplayName("없는 예약이면 NOT_FOUND")
        void notFound() {
            UUID reservationId = UUID.randomUUID();
            given(reservationRepository.findById(reservationId)).willReturn(Optional.empty());

            assertErrorCode(() -> reservationService.getReservation(UUID.randomUUID(), reservationId, RoleType.REQUESTER),
                    ErrorCode.RESERVATION_NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("deleteReservation")
    class DeleteReservation {
        @Test
        @DisplayName("본인의 종료된 예약은 soft delete된다")
        void success() {
            Reservation reservation = reservationIn(ReservationStatus.FAILED, referral);
            given(reservationRepository.findById(reservation.getId())).willReturn(Optional.of(reservation));

            reservationService.deleteReservation(USER_ID, reservation.getId(), RoleType.REQUESTER);

            assertThat(reservation.getDeletedAt()).isNotNull();
        }

        @Test
        @DisplayName("다른 사람의 예약은 삭제할 수 없다")
        void otherUser() {
            Reservation reservation = reservationIn(ReservationStatus.FAILED, referral);
            given(reservationRepository.findById(reservation.getId())).willReturn(Optional.of(reservation));

            assertErrorCode(() -> reservationService.deleteReservation(OTHER_USER_ID, reservation.getId(), RoleType.REQUESTER),
                    ErrorCode.RESERVATION_NOT_FOUND);
            assertThat(reservation.getDeletedAt()).isNull();
        }

        @Test
        @DisplayName("진행 중인 예약은 삭제할 수 없다")
        void invalidStatus() {
            Reservation reservation = reservationIn(ReservationStatus.CONFIRMED, referral);
            given(reservationRepository.findById(reservation.getId())).willReturn(Optional.of(reservation));

            assertErrorCode(() -> reservationService.deleteReservation(USER_ID, reservation.getId(), RoleType.REQUESTER),
                    ErrorCode.INVALID_RESERVATION_STATUS);
        }
    }

    private void assertErrorCode(Runnable action, ErrorCode errorCode) {
        assertThatThrownBy(action::run)
                .isInstanceOf(BaseException.class)
                .extracting("errorCode")
                .isEqualTo(errorCode);
    }
}
