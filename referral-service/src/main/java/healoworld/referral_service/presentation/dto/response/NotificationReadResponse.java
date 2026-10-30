package healoworld.referral_service.presentation.dto.response;

import healoworld.referral_service.domain.entity.Notification;

import java.time.LocalDateTime;
import java.util.UUID;

// PATCH /api/v1/notifications/{notificationId}/read 응답 (명세: notificationId, isRead, readAt)
public record NotificationReadResponse(UUID notificationId, boolean isRead, LocalDateTime readAt) {

    public static NotificationReadResponse from(Notification notification) {
        return new NotificationReadResponse(notification.getId(), notification.isRead(), notification.getReadAt());
    }
}
