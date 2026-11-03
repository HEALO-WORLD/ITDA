package healoworld.referral_service.application.service;

import healoworld.referral_service.domain.entity.Referral;
import healoworld.referral_service.domain.entity.ReferralHistory;
import healoworld.referral_service.domain.entity.Reservation;
import healoworld.referral_service.domain.enums.FailureReason;
import healoworld.referral_service.domain.enums.Reason;
import healoworld.referral_service.domain.enums.ReferralStatus;
import healoworld.referral_service.domain.enums.ReservationStatus;
import healoworld.referral_service.global.exception.BaseException;
import healoworld.referral_service.global.exception.ErrorCode;
import healoworld.referral_service.global.outbox.SlotCommandOutbox;
import healoworld.referral_service.infrastructure.repository.ReferralHistoryRepository;
import healoworld.referral_service.infrastructure.repository.ReferralRepository;
import healoworld.referral_service.infrastructure.repository.ReservationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static healoworld.referral_service.support.DomainFixtures.referralIn;
import static healoworld.referral_service.support.DomainFixtures.reservationIn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class SlotResultServiceTest {
    private static final UUID USER_ID = UUID.randomUUID();

    @Mock
    private ReservationRepository reservationRepository;

    @Mock
    private ReferralRepository referralRepository;

    @Mock
    private ReferralHistoryRepository referralHistoryRepository;

    @Mock
    private SlotCommandOutbox slotCommandOutbox;

    @InjectMocks
    private SlotResultService slotResultService;

    private Referral referral;

    @BeforeEach
    void setUp() {
        referral = referralIn(ReferralStatus.PENDING, USER_ID);
    }

    @Test
    @DisplayName("SlotHeld: REQUESTED → HELD, BookSlot을 발행하고 의뢰 상태는 그대로라 이력은 없다")
    void slotHeld() {
        Reservation reservation = stored(ReservationStatus.REQUESTED, referral);

        handle("SlotHeld", reservation);

        assertThat(reservation.getReservationStatus()).isEqualTo(ReservationStatus.HELD);
        assertThat(referral.getReferralStatus()).isEqualTo(ReferralStatus.PENDING);
        verify(slotCommandOutbox).recordOutbox(reservation, "BookSlot");
        verifyNoInteractions(referralHistoryRepository);
    }

    @Test
    @DisplayName("SlotHoldFailed: 예약 FAILED(SLOT_CONFLICT), 의뢰 FAILED, 이력은 수신 eventId로 남는다")
    void slotHoldFailed() {
        Reservation reservation = stored(ReservationStatus.REQUESTED, referral);

        UUID eventId = handle("SlotHoldFailed", reservation);

        assertThat(reservation.getReservationStatus()).isEqualTo(ReservationStatus.FAILED);
        assertThat(reservation.getFailureReason()).isEqualTo(FailureReason.SLOT_CONFLICT);
        assertHistory(ReferralStatus.PENDING, ReferralStatus.FAILED, Reason.RESERVATION_FAILED, eventId);
        verifyNoInteractions(slotCommandOutbox);
    }

    @Test
    @DisplayName("SlotBooked: 예약·의뢰 CONFIRMED, 이력은 수신 eventId로 남는다")
    void slotBooked() {
        Reservation reservation = stored(ReservationStatus.HELD, referral);

        UUID eventId = handle("SlotBooked", reservation);

        assertThat(reservation.getReservationStatus()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(reservation.getConfirmedAt()).isNotNull();
        assertHistory(ReferralStatus.PENDING, ReferralStatus.CONFIRMED, Reason.RESERVATION_CONFIRMED, eventId);
    }

    @Test
    @DisplayName("SlotBookFailed: 예약 FAILED(CONFIRM_FAILED), ReleaseHold 발행, 이력은 발행 eventId로 남는다")
    void slotBookFailed() {
        Reservation reservation = stored(ReservationStatus.HELD, referral);
        UUID releaseEventId = UUID.randomUUID();
        given(slotCommandOutbox.recordOutbox(reservation, "ReleaseHold")).willReturn(releaseEventId);

        handle("SlotBookFailed", reservation);

        assertThat(reservation.getReservationStatus()).isEqualTo(ReservationStatus.FAILED);
        assertThat(reservation.getFailureReason()).isEqualTo(FailureReason.CONFIRM_FAILED);
        assertHistory(ReferralStatus.PENDING, ReferralStatus.FAILED, Reason.RESERVATION_FAILED, releaseEventId);
    }

    @Test
    @DisplayName("HoldExpired: 예약 FAILED(HOLD_EXPIRED), ReleaseHold 발행, 이력은 발행 eventId로 남는다")
    void holdExpired() {
        Reservation reservation = stored(ReservationStatus.HELD, referral);
        UUID releaseEventId = UUID.randomUUID();
        given(slotCommandOutbox.recordOutbox(reservation, "ReleaseHold")).willReturn(releaseEventId);

        handle("HoldExpired", reservation);

        assertThat(reservation.getReservationStatus()).isEqualTo(ReservationStatus.FAILED);
        assertThat(reservation.getFailureReason()).isEqualTo(FailureReason.HOLD_EXPIRED);
        assertHistory(ReferralStatus.PENDING, ReferralStatus.FAILED, Reason.RESERVATION_FAILED, releaseEventId);
    }

    @ParameterizedTest
    @EnumSource(value = ReservationStatus.class, names = {"REQUESTED", "HELD", "CONFIRMED"})
    @DisplayName("SlotsBlocked: 진행 중인 예약은 BLOCKED, 의뢰는 NEEDS_RESCHEDULE, 이력의 이전 상태는 실제 의뢰 상태")
    void slotsBlocked(ReservationStatus status) {
        Referral target = referralIn(status == ReservationStatus.CONFIRMED ? ReferralStatus.CONFIRMED : ReferralStatus.PENDING, USER_ID);
        ReferralStatus before = target.getReferralStatus();
        Reservation reservation = stored(status, target);

        UUID eventId = handle("SlotsBlocked", reservation);

        assertThat(reservation.getReservationStatus()).isEqualTo(ReservationStatus.BLOCKED);
        assertThat(reservation.getFailureReason()).isEqualTo(FailureReason.EQUIPMENT_FAILURE);
        assertHistory(before, ReferralStatus.NEEDS_RESCHEDULE, Reason.RESERVATION_BLOCKED, eventId);
    }

    @Test
    @DisplayName("모르는 eventType은 상태를 바꾸지 않고 넘어간다")
    void unknownEventType() {
        Reservation reservation = stored(ReservationStatus.REQUESTED, referral);

        handle("SomethingNew", reservation);

        assertThat(reservation.getReservationStatus()).isEqualTo(ReservationStatus.REQUESTED);
        verifyNoInteractions(slotCommandOutbox, referralHistoryRepository);
    }

    @ParameterizedTest(name = "{0}: 예약이 {1}이면 무시")
    @CsvSource({
            "SlotHeld, HELD", "SlotHeld, CONFIRMED", "SlotHeld, FAILED", "SlotHeld, BLOCKED", "SlotHeld, CANCELED",
            "SlotHoldFailed, HELD", "SlotHoldFailed, CONFIRMED", "SlotHoldFailed, FAILED",
            "SlotBooked, REQUESTED", "SlotBooked, CONFIRMED", "SlotBooked, FAILED", "SlotBooked, BLOCKED",
            "SlotBookFailed, REQUESTED", "SlotBookFailed, CONFIRMED", "SlotBookFailed, FAILED",
            "HoldExpired, REQUESTED", "HoldExpired, CONFIRMED", "HoldExpired, FAILED",
            "SlotsBlocked, FAILED", "SlotsBlocked, BLOCKED", "SlotsBlocked, CANCELED"
    })
    @DisplayName("중복·지연 수신: 이벤트가 기대하는 예약 상태가 아니면 상태를 바꾸지 않고, 아웃박스·이력도 남기지 않는다")
    void ignoresUnexpectedStatus(String eventType, ReservationStatus status) {
        Reservation reservation = stored(status, referral);
        ReferralStatus referralBefore = referral.getReferralStatus();

        handle(eventType, reservation);

        assertThat(reservation.getReservationStatus()).isEqualTo(status);
        assertThat(referral.getReferralStatus()).isEqualTo(referralBefore);
        verifyNoInteractions(slotCommandOutbox, referralHistoryRepository);
    }

    @Test
    @DisplayName("예약이 없으면 RESERVATION_NOT_FOUND, 의뢰는 잠그지 않는다")
    void reservationNotFound() {
        UUID reservationId = UUID.randomUUID();
        given(reservationRepository.findReferralIdById(reservationId)).willReturn(Optional.empty());

        assertErrorCode(() -> slotResultService.handle(UUID.randomUUID(), "SlotHeld", reservationId),
                ErrorCode.RESERVATION_NOT_FOUND);
        verifyNoInteractions(referralRepository);
    }

    @Test
    @DisplayName("예약은 있는데 의뢰가 없으면 REFERRAL_NOT_FOUND")
    void referralNotFound() {
        UUID reservationId = UUID.randomUUID();
        UUID referralId = UUID.randomUUID();
        given(reservationRepository.findReferralIdById(reservationId)).willReturn(Optional.of(referralId));
        given(referralRepository.findByIdForUpdate(referralId)).willReturn(Optional.empty());

        assertErrorCode(() -> slotResultService.handle(UUID.randomUUID(), "SlotHeld", reservationId),
                ErrorCode.REFERRAL_NOT_FOUND);
    }

    @Test
    @DisplayName("payload에 referralId가 없어도 예약에서 의뢰 ID를 찾고, 의뢰를 잠근 뒤에 예약을 읽는다")
    void resolvesReferralFromReservationThenLocks() {
        Reservation reservation = stored(ReservationStatus.REQUESTED, referral);

        handle("SlotHeld", reservation);

        InOrder inOrder = inOrder(reservationRepository, referralRepository);
        inOrder.verify(reservationRepository).findReferralIdById(reservation.getId());
        inOrder.verify(referralRepository).findByIdForUpdate(referral.getId());
        inOrder.verify(reservationRepository).findById(reservation.getId());
    }

    // 예약에서 의뢰 ID 조회 → 의뢰 잠금 → 예약 조회 순서로 준비한다
    private Reservation stored(ReservationStatus status, Referral owner) {
        Reservation reservation = reservationIn(status, owner);
        given(reservationRepository.findReferralIdById(reservation.getId())).willReturn(Optional.of(owner.getId()));
        given(referralRepository.findByIdForUpdate(owner.getId())).willReturn(Optional.of(owner));
        given(reservationRepository.findById(reservation.getId())).willReturn(Optional.of(reservation));
        return reservation;
    }

    // 수신 eventId를 돌려준다
    private UUID handle(String eventType, Reservation reservation) {
        UUID eventId = UUID.randomUUID();
        slotResultService.handle(eventId, eventType, reservation.getId());
        return eventId;
    }

    private void assertHistory(ReferralStatus from, ReferralStatus to, Reason reason, UUID eventId) {
        ArgumentCaptor<ReferralHistory> captor = ArgumentCaptor.forClass(ReferralHistory.class);
        verify(referralHistoryRepository).save(captor.capture());
        ReferralHistory history = captor.getValue();
        assertThat(history.getFromStatus()).isEqualTo(from);
        assertThat(history.getToStatus()).isEqualTo(to);
        assertThat(history.getReason()).isEqualTo(reason);
        assertThat(history.getEventId()).isEqualTo(eventId);
        assertThat(history.getChangedBy()).isNull();
    }

    private void assertErrorCode(Runnable action, ErrorCode errorCode) {
        assertThatThrownBy(action::run)
                .isInstanceOf(BaseException.class)
                .extracting("errorCode")
                .isEqualTo(errorCode);
    }
}
