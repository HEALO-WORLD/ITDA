package healoworld.referral_service.application.service;

import healoworld.referral_service.domain.entity.IncidentReferral;
import healoworld.referral_service.domain.entity.Notification;
import healoworld.referral_service.domain.entity.Referral;
import healoworld.referral_service.global.dto.PagedResponse;
import healoworld.referral_service.global.exception.BaseException;
import healoworld.referral_service.global.exception.ErrorCode;
import healoworld.referral_service.infrastructure.repository.NotificationRepository;
import healoworld.referral_service.infrastructure.repository.NotificationSpecifications;
import healoworld.referral_service.presentation.dto.response.NotificationReadResponse;
import healoworld.referral_service.presentation.dto.response.NotificationResponse;
import healoworld.referral_service.presentation.dto.response.UnreadCountResponse;
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
    private static final String RESCHEDULE_REQUIRED = "RESCHEDULE_REQUIRED";
    private static final String RESCHEDULE_EXPIRED = "RESCHEDULE_EXPIRED";

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


    // 장비 장애로 재예약이 필요한 경우 의뢰 담당자에게 알림 생성
    // 중복 방지: 같은 장애 작업 또는 같은 예약으로 이미 만든 재조정 알림이 있으면 만들지 않는다
    // - 호출하는 장애 후속 처리(IncidentProcessingService)가 의뢰 행을 잠근 트랜잭션에서 부르므로
    //   같은 의뢰(예약)의 알림 생성은 순서대로 실행되고, 뒤 요청은 앞 요청이 커밋한 알림을 보고 건너뛴다
    @Transactional
    public void createRescheduleRequired(
            IncidentReferral task,
            Referral referral
    ) {
        UUID recipientUserId = referral.getReferrerId();

        // 동일한 장애 작업으로 이미 생성된 알림이 있으면 중복 생성하지 않음
        if (notificationRepository
                .existsByIncidentReferralIdAndRecipientUserIdAndType(
                        task.getId(),
                        recipientUserId,
                        RESCHEDULE_REQUIRED
                )) {
            return;
        }

        // 서로 다른 장애 작업이 같은 예약을 처리해도 같은 예약의 재조정 알림은 1건만 만든다
        if (notificationRepository
                .existsByReservationIdAndRecipientUserIdAndType(
                        task.getReservationId(),
                        recipientUserId,
                        RESCHEDULE_REQUIRED
                )) {
            return;
        }

        Notification notification = Notification.create(
                recipientUserId,
                referral.getId(),
                task.getReservationId(),
                task.getId(),
                task.getIncident().getSourceEventId(),
                RESCHEDULE_REQUIRED,
                "검사 예약 재조정 필요",
                "장비 장애로 기존 검사 예약을 진행할 수 없어 새로운 검사 일정 선택이 필요합니다."
        );

        notificationRepository.saveAndFlush(notification);
    }

    // 의뢰 행이 FOR UPDATE로 잠긴 종료 트랜잭션 안에서 호출한다.
    // 원인 예약별 1건만 생성하고, 상태 변경·이력·알림이 함께 커밋된다.
    @Transactional
    public void createRescheduleExpired(Referral referral, healoworld.referral_service.domain.entity.Reservation blockedReservation) {
        UUID userId = referral.getReferrerId();
        UUID reservationId = blockedReservation.getId();
        if (notificationRepository.existsByReservationIdAndRecipientUserIdAndType(
                reservationId, userId, RESCHEDULE_EXPIRED)) {
            return;
        }
        notificationRepository.saveAndFlush(Notification.create(
                userId, referral.getId(), reservationId, null, null,
                RESCHEDULE_EXPIRED,
                "검사 의뢰 재조정 기한 만료",
                "장비 장애 이후 재조정 기한 내 새 일정을 선택하지 않아 검사 의뢰가 종료되었습니다."
        ));
    }
}
