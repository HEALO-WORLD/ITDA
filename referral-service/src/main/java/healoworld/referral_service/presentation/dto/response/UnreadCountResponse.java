package healoworld.referral_service.presentation.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

// GET /api/v1/notifications/unread-count 응답 (명세: unreadCount)
public record UnreadCountResponse(@Schema(description = "읽지 않은 알림 수") long unreadCount) {
}
