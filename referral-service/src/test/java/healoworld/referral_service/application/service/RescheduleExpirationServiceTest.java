package healoworld.referral_service.application.service;

import healoworld.referral_service.domain.entity.Examination;
import healoworld.referral_service.domain.entity.Referral;
import healoworld.referral_service.domain.entity.Reservation;
import healoworld.referral_service.domain.enums.ExaminationStatus;
import healoworld.referral_service.domain.enums.Reason;
import healoworld.referral_service.domain.enums.ReferralStatus;
import healoworld.referral_service.domain.enums.ReservationStatus;
import healoworld.referral_service.infrastructure.repository.ExaminationRepository;
import healoworld.referral_service.infrastructure.repository.ExaminationResultRepository;
import healoworld.referral_service.infrastructure.repository.IncidentReservationRepository;
import healoworld.referral_service.infrastructure.repository.ReferralRepository;
import healoworld.referral_service.infrastructure.repository.ReservationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RescheduleExpirationServiceTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-12T03:00:00Z"), ZoneId.of("Asia/Seoul"));
    private static final UUID REFERRAL_ID = UUID.randomUUID();
    private static final UUID RESERVATION_ID = UUID.randomUUID();

    @Mock ReferralRepository referralRepository;
    @Mock IncidentReservationRepository incidentReservationRepository;
    @Mock ReservationRepository reservationRepository;
    @Mock ExaminationRepository examinationRepository;
    @Mock ExaminationResultRepository examinationResultRepository;
    @Mock ReferralHistoryService referralHistoryService;
    @Mock NotificationService notificationService;
    RescheduleExpirationService service;

    @BeforeEach
    void setup() {
        service = new RescheduleExpirationService(referralRepository, incidentReservationRepository,
                reservationRepository, examinationRepository, examinationResultRepository,
                referralHistoryService, notificationService, CLOCK, 48);
    }

    private Referral referral(ReferralStatus status) {
        Referral r = Referral.create(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "TEST", UUID.randomUUID());
        ReflectionTestUtils.setField(r, "id", REFERRAL_ID);
        ReflectionTestUtils.setField(r, "referralStatus", status);
        ReflectionTestUtils.setField(r, "currentReservationId", RESERVATION_ID);
        return r;
    }

    private Reservation blocked(Referral r, LocalDateTime start, LocalDateTime blockedAt) {
        Reservation rs = Reservation.create(r, 1, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                start.toLocalDate().atStartOfDay(), start, start.plusMinutes(20));
        ReflectionTestUtils.setField(rs, "id", RESERVATION_ID);
        ReflectionTestUtils.setField(rs, "reservationStatus", ReservationStatus.BLOCKED);
        ReflectionTestUtils.setField(rs, "endedAt", blockedAt);
        return rs;
    }

    @Test
    void overdueBlockedReferralExpiresAndWritesHistoryOnce() {
        Referral referral = referral(ReferralStatus.NEEDS_RESCHEDULE);
        Reservation blocked = blocked(referral, LocalDateTime.of(2026,11,10,9,20), LocalDateTime.of(2026,11,10,10,0));
        when(referralRepository.findByIdForUpdate(REFERRAL_ID)).thenReturn(Optional.of(referral));
        when(incidentReservationRepository.findByIdForUpdate(RESERVATION_ID)).thenReturn(Optional.of(blocked));
        when(reservationRepository.findLatestBlocked(REFERRAL_ID)).thenReturn(Optional.of(blocked));

        assertThat(service.expireIfOverdue(REFERRAL_ID)).isTrue();
        assertThat(referral.getReferralStatus()).isEqualTo(ReferralStatus.EXPIRED);
        verify(referralHistoryService).recordHistory(referral, blocked, ReferralStatus.NEEDS_RESCHEDULE,
                ReferralStatus.EXPIRED, Reason.RESCHEDULE_TIMEOUT, null, null);
        verify(notificationService).createRescheduleExpired(referral, blocked);

        assertThat(service.expireIfOverdue(REFERRAL_ID)).isFalse();
        verifyNoMoreInteractions(referralHistoryService, notificationService);
    }

    @Test
    void existingNovember10BlockedReservationExpiresAfterNovember12NineTwenty() {
        // 운영 확인 값: start_time=11/10 09:20, ended_at=11/09 22:06:24.524062
        // max(start_time, ended_at) + 48h = 11/12 09:20 KST
        Referral referral = referral(ReferralStatus.NEEDS_RESCHEDULE);
        Reservation blocked = blocked(referral, LocalDateTime.of(2026, 11, 10, 9, 20),
                LocalDateTime.of(2026, 11, 9, 22, 6, 24, 524062000));
        Examination examination = mock(Examination.class);
        when(examination.getStatus()).thenReturn(ExaminationStatus.SCHEDULED);
        when(examination.getId()).thenReturn(UUID.randomUUID());
        when(referralRepository.findByIdForUpdate(REFERRAL_ID)).thenReturn(Optional.of(referral));
        when(incidentReservationRepository.findByIdForUpdate(RESERVATION_ID)).thenReturn(Optional.of(blocked));
        when(reservationRepository.findLatestBlocked(REFERRAL_ID)).thenReturn(Optional.of(blocked));
        when(examinationRepository.findByReferralIdForUpdate(REFERRAL_ID)).thenReturn(Optional.of(examination));

        assertThat(service.expireIfOverdue(REFERRAL_ID)).isTrue();
        assertThat(referral.getReferralStatus()).isEqualTo(ReferralStatus.EXPIRED);
        verify(notificationService).createRescheduleExpired(referral, blocked);
        verify(referralHistoryService).recordHistory(referral, blocked,
                ReferralStatus.NEEDS_RESCHEDULE, ReferralStatus.EXPIRED,
                Reason.RESCHEDULE_TIMEOUT, null, null);
    }

    @Test
    void scheduledExaminationWithResultIsNotAutomaticallyExpired() {
        Referral referral = referral(ReferralStatus.NEEDS_RESCHEDULE);
        Reservation blocked = blocked(referral, LocalDateTime.of(2026, 11, 10, 9, 20),
                LocalDateTime.of(2026, 11, 9, 22, 6));
        Examination examination = mock(Examination.class);
        UUID examId = UUID.randomUUID();
        when(examination.getStatus()).thenReturn(ExaminationStatus.SCHEDULED);
        when(examination.getId()).thenReturn(examId);
        when(examinationResultRepository.existsByExaminationId(examId)).thenReturn(true);
        when(referralRepository.findByIdForUpdate(REFERRAL_ID)).thenReturn(Optional.of(referral));
        when(incidentReservationRepository.findByIdForUpdate(RESERVATION_ID)).thenReturn(Optional.of(blocked));
        when(reservationRepository.findLatestBlocked(REFERRAL_ID)).thenReturn(Optional.of(blocked));
        when(examinationRepository.findByReferralIdForUpdate(REFERRAL_ID)).thenReturn(Optional.of(examination));

        assertThat(service.expireIfOverdue(REFERRAL_ID)).isFalse();
        assertThat(referral.getReferralStatus()).isEqualTo(ReferralStatus.NEEDS_RESCHEDULE);
        verifyNoInteractions(referralHistoryService, notificationService);
    }

    @Test
    void beforeDeadlineDoesNotExpire() {
        Referral referral = referral(ReferralStatus.NEEDS_RESCHEDULE);
        Reservation blocked = blocked(referral, LocalDateTime.of(2026,11,16,9,20), LocalDateTime.of(2026,11,12,10,0));
        when(referralRepository.findByIdForUpdate(REFERRAL_ID)).thenReturn(Optional.of(referral));
        when(incidentReservationRepository.findByIdForUpdate(RESERVATION_ID)).thenReturn(Optional.of(blocked));
        when(reservationRepository.findLatestBlocked(REFERRAL_ID)).thenReturn(Optional.of(blocked));

        assertThat(service.expireIfOverdue(REFERRAL_ID)).isFalse();
        assertThat(referral.getReferralStatus()).isEqualTo(ReferralStatus.NEEDS_RESCHEDULE);
        verifyNoInteractions(referralHistoryService, notificationService);
    }

    @Test
    void alreadyRescheduledNeverExpires() {
        Referral referral = referral(ReferralStatus.PENDING);
        when(referralRepository.findByIdForUpdate(REFERRAL_ID)).thenReturn(Optional.of(referral));
        assertThat(service.expireIfOverdue(REFERRAL_ID)).isFalse();
        verifyNoInteractions(incidentReservationRepository, referralHistoryService, notificationService);
    }

    @Test
    void examinationAlreadyStartedNeverExpires() {
        Referral referral = referral(ReferralStatus.NEEDS_RESCHEDULE);
        Reservation blocked = blocked(referral, LocalDateTime.of(2026,11,10,9,20), LocalDateTime.of(2026,11,10,10,0));
        Examination examination = mock(Examination.class);
        when(examination.getStatus()).thenReturn(ExaminationStatus.IN_PROGRESS);
        when(referralRepository.findByIdForUpdate(REFERRAL_ID)).thenReturn(Optional.of(referral));
        when(incidentReservationRepository.findByIdForUpdate(RESERVATION_ID)).thenReturn(Optional.of(blocked));
        when(reservationRepository.findLatestBlocked(REFERRAL_ID)).thenReturn(Optional.of(blocked));
        when(examinationRepository.findByReferralIdForUpdate(REFERRAL_ID)).thenReturn(Optional.of(examination));
        assertThat(service.expireIfOverdue(REFERRAL_ID)).isFalse();
        verifyNoInteractions(referralHistoryService, notificationService);
    }

    @Test
    void inFlightRescheduleRequestNeverExpires() {
        Referral referral = referral(ReferralStatus.NEEDS_RESCHEDULE);
        Reservation rs = blocked(referral, LocalDateTime.of(2026,11,10,9,20), LocalDateTime.of(2026,11,10,10,0));
        ReflectionTestUtils.setField(rs, "reservationStatus", ReservationStatus.HELD);
        when(referralRepository.findByIdForUpdate(REFERRAL_ID)).thenReturn(Optional.of(referral));
        when(incidentReservationRepository.findByIdForUpdate(RESERVATION_ID)).thenReturn(Optional.of(rs));
        assertThat(service.expireIfOverdue(REFERRAL_ID)).isFalse();
        verifyNoInteractions(reservationRepository, referralHistoryService, notificationService);
    }
}
