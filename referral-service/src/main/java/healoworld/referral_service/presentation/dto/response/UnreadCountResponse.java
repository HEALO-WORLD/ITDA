package healoworld.referral_service.presentation.dto.response;

// GET /api/v1/notifications/unread-count 응답 (명세: unreadCount)
public record UnreadCountResponse(long unreadCount) {
}
