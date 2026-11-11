package healoworld.referral_service.application.service;

import healoworld.referral_service.global.exception.BaseException;
import healoworld.referral_service.global.exception.ErrorCode;
import healoworld.referral_service.presentation.dto.response.NotificationReadResponse;
import healoworld.referral_service.domain.entity.EquipmentIncident;
import healoworld.referral_service.domain.entity.IncidentReferral;
import healoworld.referral_service.domain.entity.Notification;
import healoworld.referral_service.domain.entity.Referral;
import healoworld.referral_service.domain.enums.ReferralStatus;
import healoworld.referral_service.infrastructure.repository.NotificationRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static healoworld.referral_service.support.DomainFixtures.referralIn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    @Mock
    private NotificationRepository notificationRepository;

    @InjectMocks
    private NotificationService notificationService;

    private Notification notification(UUID id, UUID recipientUserId, boolean isRead, LocalDateTime readAt) {
        Notification notification = Notification.create(recipientUserId, UUID.randomUUID(), null, null, null, "TEST", "제목", "내용");
        ReflectionTestUtils.setField(notification, "id", id);
        ReflectionTestUtils.setField(notification, "isRead", isRead);
        ReflectionTestUtils.setField(notification, "readAt", readAt);
        return notification;
    }

    @Test
    @DisplayName("존재하지 않는 알림이면 NOTIFICATION_NOT_FOUND")
    void markAsReadNotFound() {
        UUID notificationId = UUID.randomUUID();
        given(notificationRepository.findById(notificationId)).willReturn(Optional.empty());

        assertThatThrownBy(() -> notificationService.markAsRead(UUID.randomUUID(), notificationId))
                .isInstanceOf(BaseException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.NOTIFICATION_NOT_FOUND);
    }

    @Test
    @DisplayName("다른 사용자의 알림이면 NOTIFICATION_ACCESS_DENIED이고 UPDATE하지 않는다")
    void markAsReadOtherUsersNotification() {
        UUID notificationId = UUID.randomUUID();
        given(notificationRepository.findById(notificationId))
                .willReturn(Optional.of(notification(notificationId, UUID.randomUUID(), false, null)));

        assertThatThrownBy(() -> notificationService.markAsRead(UUID.randomUUID(), notificationId))
                .isInstanceOf(BaseException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.NOTIFICATION_ACCESS_DENIED);
        verify(notificationRepository, never()).markAsReadIfUnread(any(), any(), any());
    }

    @Test
    @DisplayName("이미 읽은 알림이면 UPDATE 없이 기존 readAt을 그대로 반환한다")
    void markAsReadAlreadyRead() {
        UUID me = UUID.randomUUID();
        UUID notificationId = UUID.randomUUID();
        LocalDateTime readAt = LocalDateTime.of(2026, 10, 27, 10, 0);
        given(notificationRepository.findById(notificationId))
                .willReturn(Optional.of(notification(notificationId, me, true, readAt)));

        NotificationReadResponse response = notificationService.markAsRead(me, notificationId);

        assertThat(response.isRead()).isTrue();
        assertThat(response.readAt()).isEqualTo(readAt);
        verify(notificationRepository, never()).markAsReadIfUnread(any(), any(), any());
    }

    @Test
    @DisplayName("안 읽은 알림이면 조건부 UPDATE 후 DB 값을 다시 읽어 반환한다")
    void markAsReadUnread() {
        UUID me = UUID.randomUUID();
        UUID notificationId = UUID.randomUUID();
        LocalDateTime readAt = LocalDateTime.of(2026, 10, 27, 11, 0);
        given(notificationRepository.findById(notificationId))
                .willReturn(Optional.of(notification(notificationId, me, false, null)))
                .willReturn(Optional.of(notification(notificationId, me, true, readAt)));

        NotificationReadResponse response = notificationService.markAsRead(me, notificationId);

        verify(notificationRepository).markAsReadIfUnread(eq(notificationId), eq(me), any(LocalDateTime.class));
        assertThat(response.notificationId()).isEqualTo(notificationId);
        assertThat(response.isRead()).isTrue();
        assertThat(response.readAt()).isEqualTo(readAt);
    }

    @Test
    @DisplayName("안 읽은 알림 수를 반환한다")
    void countUnread() {
        UUID me = UUID.randomUUID();
        given(notificationRepository.countByRecipientUserIdAndIsReadFalse(me)).willReturn(3L);

        assertThat(notificationService.countUnread(me).unreadCount()).isEqualTo(3L);
    }

    // ===== 재조정 필요 알림 생성 (중복 방지) =====

    private IncidentReferral task(UUID referralId, UUID reservationId) {
        LocalDateTime start = LocalDateTime.of(2026, 11, 10, 10, 0);
        EquipmentIncident incident = EquipmentIncident.occur(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "가상 장애", start, start, start.plusHours(2));
        IncidentReferral task = IncidentReferral.pending(incident, referralId, reservationId);
        ReflectionTestUtils.setField(task, "id", UUID.randomUUID());
        return task;
    }

    @Test
    @DisplayName("재조정 알림: 같은 작업·같은 예약의 알림이 없으면 1건 저장한다")
    void createRescheduleRequiredSaves() {
        Referral referral = referralIn(ReferralStatus.NEEDS_RESCHEDULE, UUID.randomUUID());
        IncidentReferral task = task(referral.getId(), UUID.randomUUID());

        notificationService.createRescheduleRequired(task, referral);

        ArgumentCaptor<Notification> saved = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getReservationId()).isEqualTo(task.getReservationId());
        assertThat(saved.getValue().getIncidentReferralId()).isEqualTo(task.getId());
        assertThat(saved.getValue().getRecipientUserId()).isEqualTo(referral.getReferrerId());
    }

    @Test
    @DisplayName("재조정 알림: 다른 장애 작업이 같은 예약으로 이미 만든 알림이 있으면 새로 만들지 않는다")
    void createRescheduleRequiredSkipsSameReservation() {
        Referral referral = referralIn(ReferralStatus.NEEDS_RESCHEDULE, UUID.randomUUID());
        IncidentReferral task = task(referral.getId(), UUID.randomUUID());
        given(notificationRepository.existsByReservationIdAndRecipientUserIdAndType(
                task.getReservationId(), referral.getReferrerId(), "RESCHEDULE_REQUIRED")).willReturn(true);

        notificationService.createRescheduleRequired(task, referral);

        verify(notificationRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("재조정 알림: 같은 장애 작업으로 이미 만든 알림이 있으면 새로 만들지 않는다 (기존 중복 방지 유지)")
    void createRescheduleRequiredSkipsSameTask() {
        Referral referral = referralIn(ReferralStatus.NEEDS_RESCHEDULE, UUID.randomUUID());
        IncidentReferral task = task(referral.getId(), UUID.randomUUID());
        given(notificationRepository.existsByIncidentReferralIdAndRecipientUserIdAndType(
                task.getId(), referral.getReferrerId(), "RESCHEDULE_REQUIRED")).willReturn(true);

        notificationService.createRescheduleRequired(task, referral);

        verify(notificationRepository, never()).saveAndFlush(any());
    }
}
