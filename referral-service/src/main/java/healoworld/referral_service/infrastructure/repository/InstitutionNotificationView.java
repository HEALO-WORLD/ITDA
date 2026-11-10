package healoworld.referral_service.infrastructure.repository;

import healoworld.referral_service.domain.entity.InstitutionNotification;

import java.time.LocalDateTime;

// 기관 알림 1건 + 조회한 사용자의 처음 읽은 시각 (읽음 기록이 없으면 null = 안 읽음)
public record InstitutionNotificationView(InstitutionNotification notification, LocalDateTime readAt) {

    public boolean isRead() {
        return readAt != null;
    }
}
