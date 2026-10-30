package healoworld.referral_service.application.service;

import healoworld.referral_service.application.service.IncidentProcessingService.Outcome;
import healoworld.referral_service.domain.entity.IncidentReferral;
import healoworld.referral_service.domain.entity.Referral;
import healoworld.referral_service.domain.entity.Reservation;
import healoworld.referral_service.domain.enums.ReferralStatus;
import healoworld.referral_service.domain.enums.ReservationStatus;
import healoworld.referral_service.infrastructure.repository.IncidentAffectedReferralRepository;
import healoworld.referral_service.infrastructure.repository.IncidentReferralRepository;
import healoworld.referral_service.infrastructure.repository.IncidentReservationRepository;
import healoworld.referral_service.support.DomainFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

// 상태 판단 분기, 성공·실패 기록, 잠금 상실 처리를 Mock으로 검증한다 (트랜잭션 매니저도 Mock이라 실제 트랜잭션은 없다)
@ExtendWith(MockitoExtension.class)
class IncidentProcessingServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 2, 14, 0, 0, 123_456_789);
    private static final LocalDateTime CLAIMED_AT = NOW.truncatedTo(ChronoUnit.MICROS);
    private static final LocalDateTime LOCKED_UNTIL = CLAIMED_AT.plusMinutes(5);

    @Mock
    private IncidentReferralRepository incidentReferralRepository;

    @Mock
    private IncidentAffectedReferralRepository incidentAffectedReferralRepository;

    @Mock
    private IncidentReservationRepository incidentReservationRepository;

    @Mock
    private PlatformTransactionManager transactionManager;

    private IncidentProcessingService service;

    private final UUID taskId = UUID.randomUUID();
    private Referral referral;
    private Reservation reservation;

    @BeforeEach
    void setUp() {
        service = new IncidentProcessingService(incidentReferralRepository, incidentAffectedReferralRepository,
                incidentReservationRepository, transactionManager, Duration.ofMinutes(5), Duration.ofMinutes(1));
    }

    // 의뢰·예약을 원하는 상태로 만들고 의뢰의 현재 예약을 그 예약으로 연결한다
    private void givenState(ReferralStatus referralStatus, ReservationStatus reservationStatus) {
        referral = DomainFixtures.referralIn(referralStatus, UUID.randomUUID());
        reservation = DomainFixtures.reservationIn(reservationStatus, referral);
        ReflectionTestUtils.setField(referral, "currentReservationId", reservation.getId());
    }

    private void givenClaimedTask(UUID referralId, UUID reservationId) {
        given(incidentReferralRepository.claim(taskId, CLAIMED_AT, LOCKED_UNTIL)).willReturn(1);
        given(incidentReferralRepository.findById(taskId))
                .willReturn(Optional.of(IncidentReferral.pending(null, referralId, reservationId)));
    }

    private void givenLockedRows() {
        givenClaimedTask(referral.getId(), reservation.getId());
        given(incidentAffectedReferralRepository.findByIdForUpdate(referral.getId())).willReturn(Optional.of(referral));
        given(incidentReservationRepository.findByIdForUpdate(reservation.getId()))
                .willReturn(Optional.of(reservation));
    }

    private void givenMarkSucceeded(int updated) {
        given(incidentReferralRepository.markSucceeded(eq(taskId), eq(LOCKED_UNTIL), anyString(), any()))
                .willReturn(updated);
    }

    private void givenMarkFailed(int updated) {
        given(incidentReferralRepository.markFailed(eq(taskId), eq(LOCKED_UNTIL), anyString(), any(), any()))
                .willReturn(updated);
    }

    private String capturedLastError() {
        ArgumentCaptor<String> lastError = ArgumentCaptor.forClass(String.class);
        then(incidentReferralRepository).should().markFailed(eq(taskId), eq(LOCKED_UNTIL), lastError.capture(), any(),
                any());
        return lastError.getValue();
    }

    private void assertSucceededWith(String resultCode) {
        then(incidentReferralRepository).should().markSucceeded(eq(taskId), eq(LOCKED_UNTIL), eq(resultCode), any());
        then(incidentReferralRepository).should(never()).markFailed(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("선점(claim)이 0이면 아무것도 조회·변경하지 않고 NOT_CLAIMED")
    void notClaimed() {
        given(incidentReferralRepository.claim(taskId, CLAIMED_AT, LOCKED_UNTIL)).willReturn(0);

        assertThat(service.process(taskId, NOW)).isEqualTo(Outcome.NOT_CLAIMED);
        then(incidentAffectedReferralRepository).shouldHaveNoInteractions();
        then(incidentReservationRepository).shouldHaveNoInteractions();
        then(incidentReferralRepository).should(never()).markSucceeded(any(), any(), any(), any());
    }

    @Test
    @DisplayName("선점 시 lockedUntil은 마이크로초로 자른 현재 시각 + 잠금 시간(5분)이다")
    void claimUsesTruncatedLease() {
        given(incidentReferralRepository.claim(any(), any(), any())).willReturn(0);

        service.process(taskId, NOW);

        then(incidentReferralRepository).should().claim(taskId, CLAIMED_AT, LOCKED_UNTIL);
    }

    @Test
    @DisplayName("의뢰·예약이 모두 CONFIRMED이고 현재 예약이면 예약 BLOCKED, 의뢰 NEEDS_RESCHEDULE, RESCHEDULE_REQUIRED")
    void normalProcessing() {
        givenState(ReferralStatus.CONFIRMED, ReservationStatus.CONFIRMED);
        givenLockedRows();
        givenMarkSucceeded(1);

        assertThat(service.process(taskId, NOW)).isEqualTo(Outcome.SUCCEEDED);
        assertThat(reservation.getReservationStatus()).isEqualTo(ReservationStatus.BLOCKED);
        assertThat(referral.getReferralStatus()).isEqualTo(ReferralStatus.NEEDS_RESCHEDULE);
        assertSucceededWith(IncidentProcessingService.RESCHEDULE_REQUIRED);
    }

    @Test
    @DisplayName("이미 NEEDS_RESCHEDULE + BLOCKED면 상태를 다시 바꾸지 않고 ALREADY_BLOCKED")
    void alreadyBlocked() {
        givenState(ReferralStatus.NEEDS_RESCHEDULE, ReservationStatus.BLOCKED);
        LocalDateTime endedAt = reservation.getEndedAt();
        givenLockedRows();
        givenMarkSucceeded(1);

        assertThat(service.process(taskId, NOW)).isEqualTo(Outcome.SUCCEEDED);
        assertThat(reservation.getEndedAt()).isEqualTo(endedAt);
        assertSucceededWith(IncidentProcessingService.ALREADY_BLOCKED);
    }

    @Test
    @DisplayName("의뢰가 CANCELED면 아무것도 바꾸지 않고 SKIPPED_CANCELED")
    void canceledReferral() {
        givenState(ReferralStatus.CANCELED, ReservationStatus.CANCELED);
        givenLockedRows();
        givenMarkSucceeded(1);

        assertThat(service.process(taskId, NOW)).isEqualTo(Outcome.SUCCEEDED);
        assertThat(referral.getReferralStatus()).isEqualTo(ReferralStatus.CANCELED);
        assertSucceededWith(IncidentProcessingService.SKIPPED_CANCELED);
    }

    @Test
    @DisplayName("현재 예약이 CANCELED면(의뢰는 CONFIRMED) 아무것도 바꾸지 않고 SKIPPED_CANCELED")
    void canceledReservation() {
        givenState(ReferralStatus.CONFIRMED, ReservationStatus.CANCELED);
        givenLockedRows();
        givenMarkSucceeded(1);

        assertThat(service.process(taskId, NOW)).isEqualTo(Outcome.SUCCEEDED);
        assertThat(referral.getReferralStatus()).isEqualTo(ReferralStatus.CONFIRMED);
        assertThat(reservation.getReservationStatus()).isEqualTo(ReservationStatus.CANCELED);
        assertSucceededWith(IncidentProcessingService.SKIPPED_CANCELED);
    }

    @Test
    @DisplayName("의뢰의 현재 예약이 다른 예약이면(재예약됨) 아무것도 바꾸지 않고 SKIPPED_NOT_CURRENT")
    void notCurrentReservation() {
        givenState(ReferralStatus.CONFIRMED, ReservationStatus.CONFIRMED);
        ReflectionTestUtils.setField(referral, "currentReservationId", UUID.randomUUID());
        givenLockedRows();
        givenMarkSucceeded(1);

        assertThat(service.process(taskId, NOW)).isEqualTo(Outcome.SUCCEEDED);
        assertThat(reservation.getReservationStatus()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(referral.getReferralStatus()).isEqualTo(ReferralStatus.CONFIRMED);
        assertSucceededWith(IncidentProcessingService.SKIPPED_NOT_CURRENT);
    }

    @Test
    @DisplayName("markSucceeded가 0이면(잠금 상실) LEASE_LOST이고 실패로 기록하지 않는다")
    void leaseLostOnSuccess() {
        givenState(ReferralStatus.CONFIRMED, ReservationStatus.CONFIRMED);
        givenLockedRows();
        givenMarkSucceeded(0);

        assertThat(service.process(taskId, NOW)).isEqualTo(Outcome.LEASE_LOST);
        then(incidentReferralRepository).should(never()).markFailed(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("처리 중 예외가 나면 같은 잠금 값으로 FAILED를 기록하고, 다음 재시도 시각은 1분 뒤다")
    void exceptionIsRecordedAsFailed() {
        givenState(ReferralStatus.CONFIRMED, ReservationStatus.CONFIRMED);
        givenLockedRows();
        given(incidentReferralRepository.markSucceeded(any(), any(), any(), any()))
                .willThrow(new IllegalStateException("가상 DB 오류"));
        givenMarkFailed(1);

        assertThat(service.process(taskId, NOW)).isEqualTo(Outcome.FAILED);
        assertThat(capturedLastError()).startsWith(IncidentProcessingService.RETRYABLE)
                .contains("IllegalStateException", "가상 DB 오류");

        ArgumentCaptor<LocalDateTime> nextRetryAt = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> failedAt = ArgumentCaptor.forClass(LocalDateTime.class);
        then(incidentReferralRepository).should().markFailed(eq(taskId), eq(LOCKED_UNTIL), anyString(),
                nextRetryAt.capture(), failedAt.capture());
        assertThat(nextRetryAt.getValue()).isEqualTo(failedAt.getValue().plusMinutes(1));
    }

    @Test
    @DisplayName("실패 기록(markFailed)이 0이면 잠금이 이미 넘어간 것이므로 LEASE_LOST")
    void leaseLostOnFailure() {
        givenState(ReferralStatus.CONFIRMED, ReservationStatus.CONFIRMED);
        givenLockedRows();
        given(incidentReferralRepository.markSucceeded(any(), any(), any(), any()))
                .willThrow(new IllegalStateException("가상 DB 오류"));
        givenMarkFailed(0);

        assertThat(service.process(taskId, NOW)).isEqualTo(Outcome.LEASE_LOST);
    }

    @Test
    @DisplayName("예약이 작업의 의뢰에 속하지 않으면 상태를 바꾸지 않고 DATA_INCONSISTENCY로 FAILED")
    void reservationOfOtherReferral() {
        givenState(ReferralStatus.CONFIRMED, ReservationStatus.CONFIRMED);
        Referral otherReferral = DomainFixtures.referralIn(ReferralStatus.CONFIRMED, UUID.randomUUID());
        ReflectionTestUtils.setField(otherReferral, "currentReservationId", reservation.getId());
        givenClaimedTask(otherReferral.getId(), reservation.getId());
        given(incidentAffectedReferralRepository.findByIdForUpdate(otherReferral.getId()))
                .willReturn(Optional.of(otherReferral));
        given(incidentReservationRepository.findByIdForUpdate(reservation.getId()))
                .willReturn(Optional.of(reservation));
        givenMarkFailed(1);

        assertThat(service.process(taskId, NOW)).isEqualTo(Outcome.FAILED);
        assertThat(capturedLastError()).startsWith(IncidentProcessingService.DATA_INCONSISTENCY);
        assertThat(reservation.getReservationStatus()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(otherReferral.getReferralStatus()).isEqualTo(ReferralStatus.CONFIRMED);
        then(incidentReferralRepository).should(never()).markSucceeded(any(), any(), any(), any());
    }

    @Test
    @DisplayName("의뢰가 없으면 예약을 조회하지 않고 DATA_INCONSISTENCY로 FAILED")
    void missingReferral() {
        UUID referralId = UUID.randomUUID();
        givenClaimedTask(referralId, UUID.randomUUID());
        given(incidentAffectedReferralRepository.findByIdForUpdate(referralId)).willReturn(Optional.empty());
        givenMarkFailed(1);

        assertThat(service.process(taskId, NOW)).isEqualTo(Outcome.FAILED);
        assertThat(capturedLastError()).startsWith(IncidentProcessingService.DATA_INCONSISTENCY);
        then(incidentReservationRepository).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("예약이 없으면 DATA_INCONSISTENCY로 FAILED")
    void missingReservation() {
        givenState(ReferralStatus.CONFIRMED, ReservationStatus.CONFIRMED);
        givenClaimedTask(referral.getId(), reservation.getId());
        given(incidentAffectedReferralRepository.findByIdForUpdate(referral.getId())).willReturn(Optional.of(referral));
        given(incidentReservationRepository.findByIdForUpdate(reservation.getId())).willReturn(Optional.empty());
        givenMarkFailed(1);

        assertThat(service.process(taskId, NOW)).isEqualTo(Outcome.FAILED);
        assertThat(capturedLastError()).startsWith(IncidentProcessingService.DATA_INCONSISTENCY);
        assertThat(referral.getReferralStatus()).isEqualTo(ReferralStatus.CONFIRMED);
    }

    @Test
    @DisplayName("의뢰 PENDING + 예약 CONFIRMED(Saga 진행 중)는 상태를 바꾸지 않고 RETRYABLE로 FAILED")
    void sagaInProgressIsRetryable() {
        givenState(ReferralStatus.PENDING, ReservationStatus.CONFIRMED);
        givenLockedRows();
        givenMarkFailed(1);

        assertThat(service.process(taskId, NOW)).isEqualTo(Outcome.FAILED);
        assertThat(capturedLastError()).startsWith(IncidentProcessingService.RETRYABLE);
        assertThat(reservation.getReservationStatus()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(referral.getReferralStatus()).isEqualTo(ReferralStatus.PENDING);
    }

    @Test
    @DisplayName("정책이 없는 조합(NEEDS_RESCHEDULE + CONFIRMED)은 상태를 바꾸지 않고 UNDECIDED_STATE로 FAILED")
    void undecidedState() {
        givenState(ReferralStatus.NEEDS_RESCHEDULE, ReservationStatus.CONFIRMED);
        givenLockedRows();
        givenMarkFailed(1);

        assertThat(service.process(taskId, NOW)).isEqualTo(Outcome.FAILED);
        assertThat(capturedLastError()).startsWith(IncidentProcessingService.UNDECIDED_STATE);
        assertThat(reservation.getReservationStatus()).isEqualTo(ReservationStatus.CONFIRMED);
    }

    @Test
    @DisplayName("실패 원인이 500자를 넘으면 500자로 잘라 기록한다")
    void lastErrorIsTruncated() {
        givenState(ReferralStatus.CONFIRMED, ReservationStatus.CONFIRMED);
        givenLockedRows();
        given(incidentReferralRepository.markSucceeded(any(), any(), any(), any()))
                .willThrow(new IllegalStateException("가".repeat(1000)));
        givenMarkFailed(1);

        service.process(taskId, NOW);

        assertThat(capturedLastError()).hasSize(500);
    }
}
