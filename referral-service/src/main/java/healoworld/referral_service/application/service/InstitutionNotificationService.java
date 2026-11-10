package healoworld.referral_service.application.service;

import healoworld.referral_service.domain.entity.InstitutionNotification;
import healoworld.referral_service.domain.entity.Reservation;
import healoworld.referral_service.global.dto.PagedResponse;
import healoworld.referral_service.global.exception.BaseException;
import healoworld.referral_service.global.exception.ErrorCode;
import healoworld.referral_service.infrastructure.repository.InstitutionNotificationRepository;
import healoworld.referral_service.infrastructure.repository.InstitutionNotificationReadRepository;
import healoworld.referral_service.infrastructure.repository.ReservationRepository;
import healoworld.referral_service.presentation.dto.response.NotificationReadResponse;
import healoworld.referral_service.presentation.dto.response.NotificationResponse;
import healoworld.referral_service.presentation.dto.response.UnreadCountResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

// 검사기관 공통 알림: 기관 ID가 수신 대상이고 알림 본문은 기관당 1건이다. 같은 기관 사용자 모두가 같은 알림을 조회하며,
// 읽음 여부·안 읽은 수는 사용자마다 따로 계산한다 (사용자별 읽음 기록: p_institution_notification_reads)
// 기관 ID는 Gateway가 JWT에서 꺼내 넣은 X-Institution-Id만 쓴다 (요청 본문·파라미터의 기관 ID는 받지 않는다)
//
// 생성: 예약 확정(SlotResultService.onSlotBooked)·예약 취소(ReservationService.cancelReservation)가
//       상태 변경에 성공한 뒤 같은 트랜잭션에서 호출한다(MANDATORY). 확정·취소가 롤백되면 알림도 남지 않는다
// 중복 방지: 같은 기관·예약·유형은 UNIQUE 제약 + INSERT ... ON CONFLICT DO NOTHING으로 1건만 남는다
//           (호출하는 쪽이 inbox·조건부 UPDATE·의뢰 잠금으로 이미 한 번만 실행하므로, 제약은 마지막 안전장치다)
// 알림 내용에는 검사 일시만 넣고 환자 이름 등 개인정보는 넣지 않는다
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class InstitutionNotificationService {

    // 알림 유형 (p_institution_notifications.type)
    public static final String RESERVATION_CONFIRMED = "RESERVATION_CONFIRMED";       // 새 예약 확정 (첫 예약, 다른 기관에서 옮겨 온 재예약)
    public static final String RESERVATION_CANCELED = "RESERVATION_CANCELED";         // 의뢰기관이 확정 예약 취소
    public static final String RESERVATION_RESCHEDULED = "RESERVATION_RESCHEDULED";   // 같은 기관 안에서 일정 변경(재예약 확정)
    public static final String RESERVATION_REASSIGNED = "RESERVATION_REASSIGNED";     // 이 기관의 예약이 다른 기관으로 변경됨

    private static final DateTimeFormatter SCHEDULE = DateTimeFormatter.ofPattern("M월 d일 HH:mm");

    private final InstitutionNotificationRepository institutionNotificationRepository;
    private final InstitutionNotificationReadRepository institutionNotificationReadRepository;
    private final ReservationRepository reservationRepository;
    // 읽은 시각·생성 시각의 기준 (한국 시간)
    private final Clock clock;

    // ===== 생성 =====

    // 예약 확정 직후 검사기관 알림을 만든다. 첫 예약 확정과 재예약 확정을 구분한다
    //  - 앞서 확정됐던 예약이 없음(첫 예약, 또는 앞 시도가 모두 확정 전에 실패): 이 예약 기관에 RESERVATION_CONFIRMED
    //  - 앞서 확정됐던 예약과 같은 기관: 그 기관에 RESERVATION_RESCHEDULED 1건 (확정 알림은 따로 만들지 않는다)
    //  - 다른 기관: 이전 기관에 RESERVATION_REASSIGNED(이전 예약 기준), 새 기관에 RESERVATION_CONFIRMED 각 1건
    // 이전 예약의 기관·일정은 p_reservations의 실제 이전 확정 예약에서 읽는다
    // sourceEventId: 확정을 일으킨 SlotBooked 이벤트 ID
    @Transactional(propagation = Propagation.MANDATORY)
    public void notifyReservationConfirmed(UUID referralId, Reservation confirmed, UUID sourceEventId) {
        Optional<Reservation> previous = confirmed.getAttemptNo() > 1
                ? reservationRepository.findPreviousConfirmed(referralId, confirmed.getAttemptNo())
                : Optional.empty();

        if (previous.isEmpty()) {
            insert(confirmed.getExamInstitutionId(), referralId, confirmed.getId(), sourceEventId, RESERVATION_CONFIRMED,
                    "새로운 검사 예약이 확정되었습니다.",
                    format(confirmed.getStartTime()) + " 검사 예약이 확정되었습니다. 예약 내역을 확인해 주세요.");
            return;
        }

        Reservation before = previous.get();
        if (before.getExamInstitutionId().equals(confirmed.getExamInstitutionId())) {
            insert(confirmed.getExamInstitutionId(), referralId, confirmed.getId(), sourceEventId, RESERVATION_RESCHEDULED,
                    "검사 예약 일정이 변경되었습니다.",
                    "검사 예약 일정이 " + format(before.getStartTime()) + "에서 " + format(confirmed.getStartTime())
                            + "(으)로 변경되었습니다. 예약 내역을 확인해 주세요.");
            return;
        }

        // 이전 기관은 자기 기관 예약(이전 예약)만 상세 조회할 수 있으므로 이전 예약 ID로 남긴다
        insert(before.getExamInstitutionId(), referralId, before.getId(), sourceEventId, RESERVATION_REASSIGNED,
                "검사 예약이 다른 기관으로 변경되었습니다.",
                format(before.getStartTime()) + " 검사 예약이 다른 검사기관으로 변경되었습니다. 검사 일정을 확인해 주세요.");
        insert(confirmed.getExamInstitutionId(), referralId, confirmed.getId(), sourceEventId, RESERVATION_CONFIRMED,
                "새로운 검사 예약이 확정되었습니다.",
                format(confirmed.getStartTime()) + " 검사 예약이 확정되었습니다. 예약 내역을 확인해 주세요.");
    }

    // 의뢰기관의 확정 예약 취소가 끝난 직후, 취소된 예약의 검사기관에 알림을 만든다
    // sourceEventId: 취소와 함께 기록한 ReleaseSlot Outbox 이벤트 ID
    @Transactional(propagation = Propagation.MANDATORY)
    public void notifyReservationCanceled(UUID referralId, Reservation canceled, UUID sourceEventId) {
        insert(canceled.getExamInstitutionId(), referralId, canceled.getId(), sourceEventId, RESERVATION_CANCELED,
                "검사 예약이 취소되었습니다.",
                format(canceled.getStartTime()) + " 검사 예약이 취소되었습니다. 검사 일정을 확인해 주세요.");
    }

    private void insert(UUID institutionId, UUID referralId, UUID reservationId, UUID sourceEventId, String type,
                        String title, String message) {
        int inserted = institutionNotificationRepository.insertIfAbsent(UUID.randomUUID(), institutionId, referralId,
                reservationId, sourceEventId, type, title, message, now());
        if (inserted == 0) {
            log.info("이미 있는 검사기관 알림, 건너뜀. institutionId={}, reservationId={}, type={}, sourceEventId={}",
                    institutionId, reservationId, type, sourceEventId);
        }
    }

    private static String format(LocalDateTime time) {
        return time.format(SCHEDULE);
    }

    // ===== 조회·읽음 (읽음 여부는 사용자별) =====
    // institutionId·userId는 Gateway가 JWT에서 꺼낸 X-Institution-Id·X-User-Id다 (요청 본문·쿼리의 ID는 받지 않는다)

    // 기관 알림 목록. isRead·readAt은 요청한 사용자의 읽음 기록 기준이다
    // referralId, isRead는 선택 필터, 페이지·정렬은 개인 알림과 같은 정책 (필터·정렬·건수는 DB 조회에서 계산)
    public PagedResponse<NotificationResponse> getInstitutionNotifications(UUID institutionId, UUID userId,
                                                                           UUID referralId, Boolean isRead,
                                                                           Pageable pageable) {
        return PagedResponse.from(institutionNotificationRepository
                .searchForUser(institutionId, userId, referralId, isRead, pageable)
                .map(view -> NotificationResponse.from(view.notification(), view.readAt())));
    }

    // 기관 알림 중 이 사용자가 아직 읽지 않은 수 (같은 기관의 다른 사용자가 읽어도 줄지 않는다)
    public UnreadCountResponse countUnread(UUID institutionId, UUID userId) {
        return new UnreadCountResponse(institutionNotificationRepository.countUnreadForUser(institutionId, userId));
    }

    // 기관 알림을 이 사용자에 대해서만 읽음 처리한다
    // - 다른 기관 알림이면 403, 없으면 404 (읽음 기록을 만들기 전에 확인)
    // - 읽음 기록은 (알림, 사용자)당 1건. 이미 있으면(재요청·동시 요청) 새로 만들지 않고 처음 읽은 시각을 돌려준다 (멱등)
    // - 다른 사용자의 읽음 기록은 키가 달라 바뀌지 않는다
    @Transactional
    public NotificationReadResponse markAsRead(UUID institutionId, UUID userId, UUID notificationId) {
        InstitutionNotification notification = findOwnedNotification(institutionId, notificationId);

        institutionNotificationReadRepository.insertIfAbsent(UUID.randomUUID(), notification.getId(), userId, now());

        // 이번에 넣었든 이미 있었든 DB에 남은 처음 기록을 읽는다
        LocalDateTime readAt = institutionNotificationReadRepository.findReadAt(notification.getId(), userId)
                .orElseThrow(() -> new IllegalStateException("읽음 기록 저장 후 조회 실패. notificationId=" + notificationId));
        return new NotificationReadResponse(notification.getId(), true, readAt);
    }

    private InstitutionNotification findOwnedNotification(UUID institutionId, UUID notificationId) {
        InstitutionNotification notification = institutionNotificationRepository.findById(notificationId)
                .orElseThrow(() -> new BaseException(ErrorCode.NOTIFICATION_NOT_FOUND));

        if (!notification.isOwnedBy(institutionId)) {
            throw new BaseException(ErrorCode.NOTIFICATION_ACCESS_DENIED);
        }
        return notification;
    }

    // DB(TIMESTAMP)는 마이크로초까지만 저장하므로 같은 정밀도로 맞춘다
    private LocalDateTime now() {
        return LocalDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
    }
}
