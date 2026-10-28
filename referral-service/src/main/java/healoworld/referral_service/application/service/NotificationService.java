package healoworld.referral_service.application.service;

import healoworld.referral_service.global.dto.PagedResponse;
import healoworld.referral_service.global.exception.BaseException;
import healoworld.referral_service.global.exception.ErrorCode;
import healoworld.referral_service.presentation.dto.response.NotificationReadResponse;
import healoworld.referral_service.presentation.dto.response.NotificationResponse;
import healoworld.referral_service.presentation.dto.response.UnreadCountResponse;
import healoworld.referral_service.domain.entity.Notification;
import healoworld.referral_service.infrastructure.repository.NotificationRepository;
import healoworld.referral_service.infrastructure.repository.NotificationSpecifications;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class NotificationService {

    private final NotificationRepository notificationRepository;

    // 내 알림 목록 (referralId, isRead는 선택 필터)
    public PagedResponse<NotificationResponse> getMyNotifications(UUID userId, UUID referralId, Boolean isRead,
                                                                  Pageable pageable) {
        return PagedResponse.from(
                notificationRepository.findAll(NotificationSpecifications.searchMine(userId, referralId, isRead), pageable)
                        .map(NotificationResponse::from));
    }

    public UnreadCountResponse countUnread(UUID userId) {
        return new UnreadCountResponse(notificationRepository.countByRecipientUserIdAndIsReadFalse(userId));
    }

    // 읽음 처리. 이미 읽은 알림이면 아무것도 바꾸지 않고 기존 readAt을 그대로 돌려준다 (멱등)
    @Transactional
    public NotificationReadResponse markAsRead(UUID userId, UUID notificationId) {
        Notification notification = findOwnedNotification(userId, notificationId);

        if (notification.isRead()) {
            return NotificationReadResponse.from(notification);
        }

        // 동시에 들어온 다른 요청이 먼저 읽음 처리했다면 0건이 갱신되고, 먼저 저장된 readAt이 유지된다
        notificationRepository.markAsReadIfUnread(notificationId, userId, LocalDateTime.now());

        // 조건부 UPDATE는 영속성 컨텍스트를 거치지 않으므로 DB의 최신 값을 다시 읽는다
        return NotificationReadResponse.from(findOwnedNotification(userId, notificationId));
    }

    private Notification findOwnedNotification(UUID userId, UUID notificationId) {
        Notification notification = notificationRepository.findById(notificationId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOTIFICATION_NOT_FOUND));

        if (!notification.isOwnedBy(userId)) {
            throw new BaseException(ErrorCode.NOTIFICATION_ACCESS_DENIED);
        }
        return notification;
    }
}
