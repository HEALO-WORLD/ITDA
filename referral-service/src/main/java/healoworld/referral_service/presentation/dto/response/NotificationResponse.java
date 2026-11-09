package healoworld.referral_service.presentation.dto.response;

import healoworld.referral_service.domain.entity.Notification;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.UUID;

public record NotificationResponse(
        @Schema(description = "알림 ID") UUID notificationId,
        @Schema(description = "관련 의뢰 ID") UUID referralId,
        @Schema(description = "알림 유형") String type,
        @Schema(description = "알림 제목") String title,
        @Schema(description = "알림 내용") String message,
        @Schema(description = "읽음 여부") boolean isRead,
        @Schema(description = "알림 생성 시각") LocalDateTime createdAt,
        @Schema(description = "읽은 시각 (읽기 전에는 null)") LocalDateTime readAt
) {
    public static NotificationResponse from(Notification notification) {
        return new NotificationResponse(
                notification.getId(),
                notification.getReferralId(),
                notification.getType(),
                notification.getTitle(),
                notification.getMessage(),
                notification.isRead(),
                notification.getCreatedAt(),
                notification.getReadAt()
        );
    }
}
