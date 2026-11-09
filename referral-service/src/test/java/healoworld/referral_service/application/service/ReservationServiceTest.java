package healoworld.referral_service.application.service;

import healoworld.referral_service.application.event.command.SlotCommandType;
import healoworld.referral_service.domain.entity.Referral;
import healoworld.referral_service.domain.entity.ReferralHistory;
import healoworld.referral_service.domain.entity.Reservation;
import healoworld.referral_service.domain.enums.Reason;
import healoworld.referral_service.domain.enums.ReferralStatus;
import healoworld.referral_service.domain.enums.ReservationStatus;
import healoworld.referral_service.domain.enums.RoleType;
import healoworld.referral_service.global.exception.BaseException;
import healoworld.referral_service.global.exception.ErrorCode;
import healoworld.referral_service.global.outbox.SlotCommandOutbox;
import healoworld.referral_service.infrastructure.repository.ReferralHistoryRepository;
import healoworld.referral_service.infrastructure.repository.ReferralRepository;
import healoworld.referral_service.infrastructure.repository.ReservationRepository;
import healoworld.referral_service.presentation.dto.request.CreateReservationRequest;
import healoworld.referral_service.presentation.dto.response.ReservationResponse;
import healoworld.referral_service.support.ConditionalUpdateStubs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static healoworld.referral_service.support.DomainFixtures.referralIn;
import static healoworld.referral_service.support.DomainFixtures.reservationIn;
import static healoworld.referral_service.support.DomainFixtures.reservationRequest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class ReservationServiceTest {
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID OTHER_USER_ID = UUID.randomUUID();

    @Mock
    private ReservationRepository reservationRepository;

    @Mock
    private ReferralHistoryRepository referralHistoryRepository;

    @Mock
    private SlotCommandOutbox slotCommandOutbox;

    @Mock
    private ReferralRepository referralRepository;

    @InjectMocks
    private ReservationService reservationService;

    private Referral referral;

    // 조건부 UPDATE를 DB처럼 흉내 낸다 (기대 상태가 맞으면 바꾸고 1, 아니면 0)
    private ConditionalUpdateStubs updates;

    @BeforeEach
    void setUp() {
        referral = referralIn(ReferralStatus.PENDING, USER_ID);
        updates = new ConditionalUpdateStubs(reservationRepository, referralRepository);
    }

    @Nested
    @DisplayName("createReservation")
    class CreateReservation {
        @Test
        @DisplayName("첫 예약이면 attemptNo는 1이고, 넘겨받은 eventId로 HoldRequested를 기록한다")
        void firstAttempt() {
            CreateReservationRequest request = reservationRequest();
            UUID eventId = UUID.randomUUID();
            given(reservationRepository.findMaxAttemptNoIncludingDeleted(referral.getId())).willReturn(0);

            Reservation created = reservationService.createReservation(eventId, referral, request);

            assertThat(created.getAttemptNo()).isEqualTo(1);
            assertThat(created.getReservationStatus()).isEqualTo(ReservationStatus.REQUESTED);
            assertThat(created.getReferral()).isSameAs(referral);
            assertThat(created.getSlotId()).isEqualTo(request.getSlotId());
            assertThat(created.getEquipmentId()).isEqualTo(request.getEquipmentId());
            verify(reservationRepository).save(created);
            verify(slotCommandOutbox).recordOutbox(created, eventId, SlotCommandType.HOLD_REQUESTED);
        }

        @Test
        @DisplayName("이전 예약이 있으면 마지막 attemptNo + 1")
        void nextAttempt() {
            given(reservationRepository.findMaxAttemptNoIncludingDeleted(referral.getId())).willReturn(2);

            Reservation created = reservationService.createReservation(UUID.randomUUID(), referral, reservationRequest());

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
            given(reservationRepository.findByIdWithReferral(reservation.getId())).willReturn(Optional.of(reservation));

            ReservationResponse response = reservationService.getReservation(
                    referral.getReferringInstitutionId(), reservation.getId(), RoleType.REQUESTER);

            assertThat(response.getId()).isEqualTo(reservation.getId());
            assertThat(response.getReferralId()).isEqualTo(referral.getId());
        }

        @Test
        @DisplayName("검사기관은 조회할 수 있다")
        void examInstitution() {
            Reservation reservation = reservationIn(ReservationStatus.REQUESTED, referral);
            given(reservationRepository.findByIdWithReferral(reservation.getId())).willReturn(Optional.of(reservation));

            ReservationResponse response = reservationService.getReservation(
                    reservation.getExamInstitutionId(), reservation.getId(), RoleType.EXAMINER);

            assertThat(response.getId()).isEqualTo(reservation.getId());
        }

        @Test
        @DisplayName("관리자는 관계없는 기관이어도 조회할 수 있다")
        void admin() {
            Reservation reservation = reservationIn(ReservationStatus.REQUESTED, referral);
            given(reservationRepository.findByIdWithReferral(reservation.getId())).willReturn(Optional.of(reservation));

            ReservationResponse response = reservationService.getReservation(UUID.randomUUID(), reservation.getId(), RoleType.ADMIN);

            assertThat(response.getId()).isEqualTo(reservation.getId());
        }

        @Test
        @DisplayName("의뢰기관도 검사기관도 아니면 NOT_FOUND")
        void otherInstitution() {
            Reservation reservation = reservationIn(ReservationStatus.REQUESTED, referral);
            given(reservationRepository.findByIdWithReferral(reservation.getId())).willReturn(Optional.of(reservation));

            assertErrorCode(() -> reservationService.getReservation(UUID.randomUUID(), reservation.getId(), RoleType.REQUESTER),
                    ErrorCode.RESERVATION_NOT_FOUND);
        }

        @Test
        @DisplayName("없는 예약이면 NOT_FOUND")
        void notFound() {
            UUID reservationId = UUID.randomUUID();
            given(reservationRepository.findByIdWithReferral(reservationId)).willReturn(Optional.empty());

            assertErrorCode(() -> reservationService.getReservation(UUID.randomUUID(), reservationId, RoleType.REQUESTER),
                    ErrorCode.RESERVATION_NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("cancelReservation")
    class CancelReservation {
        private Referral confirmedReferral;
        private Reservation confirmed;

        @BeforeEach
        void setUp() {
            confirmedReferral = referralIn(ReferralStatus.CONFIRMED, USER_ID);
            confirmed = reservationIn(ReservationStatus.CONFIRMED, confirmedReferral);
            startsIn(confirmed, LocalDateTime.now().plusDays(1));
        }

        @Test
        @DisplayName("의뢰기관은 확정 예약을 취소하고 ReleaseSlot 발행과 취소 이력을 같은 eventId로 남긴다")
        void success() {
            UUID releaseEventId = UUID.randomUUID();
            stored(confirmed);
            given(slotCommandOutbox.recordOutbox(confirmed, SlotCommandType.RELEASE_SLOT)).willReturn(releaseEventId);

            reservationService.cancelReservation(USER_ID, confirmedReferral.getReferringInstitutionId(), RoleType.REQUESTER, confirmed.getId());

            assertThat(confirmed.getReservationStatus()).isEqualTo(ReservationStatus.CANCELED);
            assertThat(confirmedReferral.getReferralStatus()).isEqualTo(ReferralStatus.CANCELED);

            ReferralHistory history = savedHistory();
            assertThat(history.getFromStatus()).isEqualTo(ReferralStatus.CONFIRMED);
            assertThat(history.getToStatus()).isEqualTo(ReferralStatus.CANCELED);
            assertThat(history.getReason()).isEqualTo(Reason.CANCELED_BY_REQUESTER);
            assertThat(history.getEventId()).isEqualTo(releaseEventId);
            assertThat(history.getChangedBy()).isEqualTo(USER_ID);
        }

        @Test
        @DisplayName("관리자는 다른 기관의 예약도 취소할 수 있다")
        void admin() {
            stored(confirmed);

            reservationService.cancelReservation(OTHER_USER_ID, UUID.randomUUID(), RoleType.ADMIN, confirmed.getId());

            assertThat(confirmed.getReservationStatus()).isEqualTo(ReservationStatus.CANCELED);
            assertThat(savedHistory().getChangedBy()).isEqualTo(OTHER_USER_ID);
        }

        @Test
        @DisplayName("다른 기관은 취소할 수 없고 존재를 숨긴다")
        void otherInstitution() {
            stored(confirmed);

            assertErrorCode(() -> reservationService.cancelReservation(USER_ID, UUID.randomUUID(), RoleType.REQUESTER, confirmed.getId()),
                    ErrorCode.RESERVATION_NOT_FOUND);
            assertThat(confirmed.getReservationStatus()).isEqualTo(ReservationStatus.CONFIRMED);
            verifyNoInteractions(slotCommandOutbox, referralHistoryRepository);
        }

        @Test
        @DisplayName("없는 예약이면 NOT_FOUND")
        void notFound() {
            UUID reservationId = UUID.randomUUID();
            given(reservationRepository.findReferralIdById(reservationId)).willReturn(Optional.empty());

            assertErrorCode(() -> reservationService.cancelReservation(USER_ID, UUID.randomUUID(), RoleType.ADMIN, reservationId),
                    ErrorCode.RESERVATION_NOT_FOUND);
        }

        @Test
        @DisplayName("시작 2시간 전 직전까지는 취소할 수 있다")
        void justBeforeDeadline() {
            startsIn(confirmed, LocalDateTime.now().plusHours(2).plusMinutes(1));
            stored(confirmed);

            reservationService.cancelReservation(USER_ID, confirmedReferral.getReferringInstitutionId(), RoleType.REQUESTER, confirmed.getId());

            assertThat(confirmed.getReservationStatus()).isEqualTo(ReservationStatus.CANCELED);
        }

        @Test
        @DisplayName("시작 2시간 전이 지나면 취소할 수 없다")
        void afterDeadline() {
            startsIn(confirmed, LocalDateTime.now().plusHours(1).plusMinutes(59));
            stored(confirmed);

            assertErrorCode(() -> reservationService.cancelReservation(USER_ID, confirmedReferral.getReferringInstitutionId(), RoleType.REQUESTER, confirmed.getId()),
                    ErrorCode.RESERVATION_CANCEL_DEADLINE_PASSED);
            assertThat(confirmed.getReservationStatus()).isEqualTo(ReservationStatus.CONFIRMED);
            verifyNoInteractions(slotCommandOutbox, referralHistoryRepository);
        }

        @Test
        @DisplayName("이미 시작한 예약은 취소할 수 없다")
        void alreadyStarted() {
            startsIn(confirmed, LocalDateTime.now().minusHours(3));
            stored(confirmed);

            assertErrorCode(() -> reservationService.cancelReservation(USER_ID, confirmedReferral.getReferringInstitutionId(), RoleType.REQUESTER, confirmed.getId()),
                    ErrorCode.RESERVATION_CANCEL_DEADLINE_PASSED);
        }

        @Test
        @DisplayName("확정되지 않은 예약은 취소할 수 없고 아무것도 기록하지 않는다")
        void notConfirmed() {
            Reservation requested = reservationIn(ReservationStatus.REQUESTED, referral);
            startsIn(requested, LocalDateTime.now().plusDays(1));
            stored(requested);

            assertErrorCode(() -> reservationService.cancelReservation(USER_ID, referral.getReferringInstitutionId(), RoleType.REQUESTER, requested.getId()),
                    ErrorCode.INVALID_RESERVATION_STATUS);
            verifyNoInteractions(slotCommandOutbox, referralHistoryRepository);
        }

        @Test
        @DisplayName("이미 취소된 예약을 다시 취소하면 409이고 아무것도 기록하지 않는다")
        void alreadyCanceled() {
            Reservation canceled = reservationIn(ReservationStatus.CANCELED, confirmedReferral);
            startsIn(canceled, LocalDateTime.now().plusDays(1));
            stored(canceled);

            assertErrorCode(() -> reservationService.cancelReservation(USER_ID, confirmedReferral.getReferringInstitutionId(), RoleType.REQUESTER, canceled.getId()),
                    ErrorCode.INVALID_RESERVATION_STATUS);
            verifyNoInteractions(slotCommandOutbox, referralHistoryRepository);
        }

        @Test
        @DisplayName("예약은 확정인데 의뢰가 취소할 수 없는 상태면 예외로 예약 변경까지 롤백되게 하고 아무것도 기록하지 않는다")
        void referralNotCancelable() {
            Reservation reservation = reservationIn(ReservationStatus.CONFIRMED, referral);   // 의뢰는 PENDING
            startsIn(reservation, LocalDateTime.now().plusDays(1));
            stored(reservation);

            assertErrorCode(() -> reservationService.cancelReservation(USER_ID, referral.getReferringInstitutionId(), RoleType.REQUESTER, reservation.getId()),
                    ErrorCode.INVALID_REFERRAL_STATUS);
            verifyNoInteractions(slotCommandOutbox, referralHistoryRepository);
        }

        @Test
        @DisplayName("의뢰 ID만 먼저 조회하고, 의뢰를 잠근 뒤에 예약을 읽는다")
        void locksReferralBeforeReadingReservation() {
            stored(confirmed);

            reservationService.cancelReservation(USER_ID, confirmedReferral.getReferringInstitutionId(), RoleType.REQUESTER, confirmed.getId());

            InOrder inOrder = inOrder(reservationRepository, referralRepository);
            inOrder.verify(reservationRepository).findReferralIdById(confirmed.getId());
            inOrder.verify(referralRepository).findByIdForUpdate(confirmedReferral.getId());
            inOrder.verify(reservationRepository).findById(confirmed.getId());
        }

        // 의뢰 ID 조회 → 의뢰 잠금 → 예약 조회 순서로 준비한다
        private void stored(Reservation reservation) {
            Referral owner = reservation.getReferral();
            given(reservationRepository.findReferralIdById(reservation.getId())).willReturn(Optional.of(owner.getId()));
            given(referralRepository.findByIdForUpdate(owner.getId())).willReturn(Optional.of(owner));
            given(reservationRepository.findById(reservation.getId())).willReturn(Optional.of(reservation));
            updates.register(reservation);
        }
    }

    private void startsIn(Reservation reservation, LocalDateTime startTime) {
        ReflectionTestUtils.setField(reservation, "startTime", startTime);
    }

    private ReferralHistory savedHistory() {
        ArgumentCaptor<ReferralHistory> captor = ArgumentCaptor.forClass(ReferralHistory.class);
        verify(referralHistoryRepository).save(captor.capture());
        return captor.getValue();
    }

    private void assertErrorCode(Runnable action, ErrorCode errorCode) {
        assertThatThrownBy(action::run)
                .isInstanceOf(BaseException.class)
                .extracting("errorCode")
                .isEqualTo(errorCode);
    }
}
