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
import healoworld.referral_service.global.inbox.ConsumedEvent;
import healoworld.referral_service.global.inbox.ConsumedEventId;
import healoworld.referral_service.global.inbox.ConsumedEventRepository;
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
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;

import static healoworld.referral_service.support.DomainFixtures.referralIn;
import static healoworld.referral_service.support.DomainFixtures.reservationIn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

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

    @Mock
    private ConsumedEventRepository consumedEventRepository;

    @Mock
    private ExaminationService examinationService;

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

    @ParameterizedTest(name = "{0}: 재예약(attemptNo 2) 실패면 의뢰는 NEEDS_RESCHEDULE")
    @CsvSource({"SlotHoldFailed, REQUESTED", "SlotBookFailed, HELD", "HoldExpired, HELD"})
    @DisplayName("재예약 실패: 예약은 FAILED, 의뢰는 FAILED가 아니라 NEEDS_RESCHEDULE로 돌아가 다시 재예약할 수 있다")
    void retryFailureGoesBackToNeedsReschedule(String eventType, ReservationStatus status) {
        Reservation reservation = storedRetry(status, referral);

        handle(eventType, reservation);

        assertThat(reservation.getReservationStatus()).isEqualTo(ReservationStatus.FAILED);
        assertThat(referral.getReferralStatus()).isEqualTo(ReferralStatus.NEEDS_RESCHEDULE);
        ArgumentCaptor<ReferralHistory> captor = ArgumentCaptor.forClass(ReferralHistory.class);
        verify(referralHistoryRepository).save(captor.capture());
        assertThat(captor.getValue().getFromStatus()).isEqualTo(ReferralStatus.PENDING);
        assertThat(captor.getValue().getToStatus()).isEqualTo(ReferralStatus.NEEDS_RESCHEDULE);
        assertThat(captor.getValue().getReason()).isEqualTo(Reason.RESERVATION_FAILED);
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
        verify(consumedEventRepository).save(any(ConsumedEvent.class));
    }

    @Test
    @DisplayName("inbox: 처리한 이벤트는 eventId·consumer·eventType으로 기록한다")
    void recordsConsumedEvent() {
        Reservation reservation = stored(ReservationStatus.REQUESTED, referral);

        UUID eventId = handle("SlotHeld", reservation);

        ArgumentCaptor<ConsumedEvent> captor = ArgumentCaptor.forClass(ConsumedEvent.class);
        verify(consumedEventRepository).save(captor.capture());
        ConsumedEvent consumed = captor.getValue();
        assertThat(consumed.getId()).isEqualTo(new ConsumedEventId(eventId, SlotResultService.CONSUMER));
        assertThat(consumed.getEventType()).isEqualTo("SlotHeld");
        assertThat(consumed.getProcessedAt()).isNotNull();
    }

    @Test
    @DisplayName("inbox: 이미 처리한 eventId면 상태·아웃박스·이력을 건드리지 않고 다시 기록하지도 않는다")
    void skipsAlreadyConsumed() {
        Reservation reservation = stored(ReservationStatus.REQUESTED, referral);
        UUID eventId = UUID.randomUUID();
        given(consumedEventRepository.existsById(new ConsumedEventId(eventId, SlotResultService.CONSUMER))).willReturn(true);

        slotResultService.handle(eventId, "SlotHeld", reservation.getId());

        assertThat(reservation.getReservationStatus()).isEqualTo(ReservationStatus.REQUESTED);
        verifyNoInteractions(slotCommandOutbox, referralHistoryRepository);
        verify(consumedEventRepository, never()).save(any());
    }

    @Test
    @DisplayName("inbox: 이미 처리했는지는 의뢰를 잠근 뒤에 확인한다")
    void checksInboxAfterLock() {
        Reservation reservation = stored(ReservationStatus.REQUESTED, referral);

        UUID eventId = handle("SlotHeld", reservation);

        InOrder inOrder = inOrder(referralRepository, consumedEventRepository);
        inOrder.verify(referralRepository).findByIdForUpdate(referral.getId());
        inOrder.verify(consumedEventRepository).existsById(new ConsumedEventId(eventId, SlotResultService.CONSUMER));
        inOrder.verify(consumedEventRepository).save(any(ConsumedEvent.class));
    }

    @Test
    @DisplayName("inbox: 처리 중 예외가 나면 기록하지 않아 재시도 때 다시 처리된다")
    void doesNotRecordOnFailure() {
        Reservation reservation = stored(ReservationStatus.REQUESTED, referral);
        given(slotCommandOutbox.recordOutbox(reservation, "BookSlot")).willThrow(new IllegalStateException("outbox 실패"));

        assertThatThrownBy(() -> handle("SlotHeld", reservation)).isInstanceOf(IllegalStateException.class);
        verify(consumedEventRepository, never()).save(any());
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

    // 재예약으로 만든 예약(attemptNo 2)
    private Reservation storedRetry(ReservationStatus status, Referral owner) {
        Reservation reservation = stored(status, owner);
        ReflectionTestUtils.setField(reservation, "attemptNo", 2);
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

    @Test
    @DisplayName("SlotBooked 수신 시 검사 일정 생성 또는 갱신을 요청한다")
    void slotBookedSyncsExamination() {
        // given
        Reservation reservation = stored(
                ReservationStatus.HELD,
                referral
        );

        // when
        handle("SlotBooked", reservation);

        // then
        verify(examinationService).syncScheduledExamination(
                referral.getId(),
                reservation.getId(),
                reservation.getExamInstitutionId()
        );
    }
}
