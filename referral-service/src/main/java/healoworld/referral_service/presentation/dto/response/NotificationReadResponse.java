package healoworld.referral_service.presentation.dto.response;

import healoworld.referral_service.domain.entity.Notification;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.UUID;

// PATCH /api/v1/notifications/{notificationId}/read 응답 (명세: notificationId, isRead, readAt)
public record NotificationReadResponse(
        @Schema(description = "알림 ID") UUID notificationId,
        @Schema(description = "읽음 여부") boolean isRead,
        @Schema(description = "처음 읽은 시각") LocalDateTime readAt) {

    public static NotificationReadResponse from(Notification notification) {
        return new NotificationReadResponse(notification.getId(), notification.isRead(), notification.getReadAt());
    }
}
