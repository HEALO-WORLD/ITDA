package healoworld.referral_service.presentation.dto.response;

import healoworld.referral_service.domain.entity.InstitutionNotification;
import healoworld.referral_service.domain.entity.Notification;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.UUID;

// 개인 알림(REQUESTER)과 검사기관 공통 알림(EXAMINER)이 같은 응답 형식을 쓴다
// reservationId는 기존 필드 뒤에 추가한 필드다 (기존 필드·순서는 그대로). 검사기관은 의뢰 상세 권한이 없어
// 알림에서 예약 상세(GET /api/v1/referrals/reservations/{reservationId})로 이동할 때 쓴다
public record NotificationResponse(
        @Schema(description = "알림 ID") UUID notificationId,
        @Schema(description = "관련 의뢰 ID") UUID referralId,
        @Schema(description = "알림 유형") String type,
        @Schema(description = "알림 제목") String title,
        @Schema(description = "알림 내용") String message,
        @Schema(description = "읽음 여부") boolean isRead,
        @Schema(description = "알림 생성 시각") LocalDateTime createdAt,
        @Schema(description = "읽은 시각 (읽기 전에는 null)") LocalDateTime readAt,
        @Schema(description = "관련 예약 ID (예약과 연결되지 않은 알림은 null)") UUID reservationId
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
                notification.getReadAt(),
                notification.getReservationId()
        );
    }

    // 검사기관 공통 알림. isRead·readAt은 조회한 사용자의 읽음 기록 기준이다 (readAt이 null이면 안 읽음)
    public static NotificationResponse from(InstitutionNotification notification, LocalDateTime readAt) {
        return new NotificationResponse(
                notification.getId(),
                notification.getReferralId(),
                notification.getType(),
                notification.getTitle(),
                notification.getMessage(),
                readAt != null,
                notification.getCreatedAt(),
                readAt,
                notification.getReservationId()
        );
    }
}
