package healoworld.referral_service.application.service;

import healoworld.referral_service.global.config.ClockConfig;
import healoworld.referral_service.global.exception.BaseException;
import healoworld.referral_service.global.exception.ErrorCode;
import healoworld.referral_service.infrastructure.repository.InstitutionNotificationReadRepository;
import healoworld.referral_service.infrastructure.repository.InstitutionNotificationRepository;
import healoworld.referral_service.infrastructure.repository.ReservationRepository;
import healoworld.referral_service.presentation.dto.request.NotificationPageRequest;
import healoworld.referral_service.presentation.dto.response.NotificationReadResponse;
import healoworld.referral_service.presentation.dto.response.NotificationResponse;
import healoworld.referral_service.support.RepositoryTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

// 검사기관 공통 알림의 사용자별 조회·읽음 처리·안 읽은 수를 실제 PostgreSQL(Testcontainers)로 검증한다 (테스트마다 롤백)
// 기관 A 사용자 A·B는 같은 X-Institution-Id(기관 A)와 서로 다른 X-User-Id로 요청한다 (Gateway가 JWT에서 채우는 값)
// 서비스를 @Import하면 새 스프링 컨텍스트(DB 연결 풀)가 생기므로, RepositoryTestSupport 컨텍스트를 그대로 쓰고 서비스만 직접 만든다
class InstitutionNotificationQueryIntegrationTest extends RepositoryTestSupport {

    private static final LocalDateTime CREATED = LocalDateTime.of(2026, 11, 10, 9, 0);

    @Autowired
    private InstitutionNotificationRepository repository;

    @Autowired
    private InstitutionNotificationReadRepository readRepository;

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final UUID institutionA = UUID.randomUUID();
    private final UUID institutionB = UUID.randomUUID();
    private final UUID userA = UUID.randomUUID();
    private final UUID userB = UUID.randomUUID();
    private final UUID userOfB = UUID.randomUUID();

    // 읽은 시각을 비교하기 위해 서비스 시계를 바꿔 끼울 수 있게 한다
    private MutableClock clock;
    private InstitutionNotificationService service;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(CREATED.plusMinutes(10));
        service = new InstitutionNotificationService(repository, readRepository, reservationRepository, clock);
    }

    private UUID notify(UUID institutionId) {
        UUID id = UUID.randomUUID();
        repository.insertIfAbsent(id, institutionId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "RESERVATION_CONFIRMED", "새로운 검사 예약이 확정되었습니다.", "가상 메시지", CREATED);
        return id;
    }

    private List<NotificationResponse> list(UUID institutionId, UUID userId, Boolean isRead) {
        return service.getInstitutionNotifications(institutionId, userId, null, isRead,
                NotificationPageRequest.of(null, null, null)).contents();
    }

    private NotificationResponse only(UUID institutionId, UUID userId) {
        List<NotificationResponse> notifications = list(institutionId, userId, null);
        assertThat(notifications).hasSize(1);
        return notifications.getFirst();
    }

    private long unread(UUID institutionId, UUID userId) {
        return service.countUnread(institutionId, userId).unreadCount();
    }

    private long readRecords(UUID notificationId) {
        return jdbcTemplate.queryForObject(
                "select count(*) from p_institution_notification_reads where notification_id = ?", Long.class,
                notificationId);
    }

    @Test
    @DisplayName("기관 알림 1건 생성 시 같은 기관 사용자 A·B 모두 같은 알림을 안 읽음으로 본다")
    void newNotificationUnreadForAll() {
        UUID id = notify(institutionA);

        for (UUID user : List.of(userA, userB)) {
            NotificationResponse seen = only(institutionA, user);
            assertThat(seen.notificationId()).isEqualTo(id);
            assertThat(seen.isRead()).isFalse();
            assertThat(seen.readAt()).isNull();
            assertThat(unread(institutionA, user)).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("A가 읽으면 A에게만 읽음이고 B는 계속 안 읽음, 안 읽은 수도 A 0·B 1로 따로 계산된다")
    void readByAOnly() {
        UUID id = notify(institutionA);

        NotificationReadResponse readByA = service.markAsRead(institutionA, userA, id);

        assertThat(readByA.isRead()).isTrue();
        assertThat(readByA.readAt()).isEqualTo(CREATED.plusMinutes(10));
        assertThat(only(institutionA, userA).isRead()).isTrue();
        assertThat(only(institutionA, userA).readAt()).isEqualTo(CREATED.plusMinutes(10));
        assertThat(only(institutionA, userB).isRead()).isFalse();
        assertThat(only(institutionA, userB).readAt()).isNull();
        assertThat(unread(institutionA, userA)).isZero();
        assertThat(unread(institutionA, userB)).isEqualTo(1);
    }

    @Test
    @DisplayName("B가 나중에 읽으면 B의 기록이 따로 생기고, A의 처음 readAt은 그대로다")
    void readByBKeepsA() {
        UUID id = notify(institutionA);
        service.markAsRead(institutionA, userA, id);

        clock.set(CREATED.plusHours(2));
        NotificationReadResponse readByB = service.markAsRead(institutionA, userB, id);

        assertThat(readByB.readAt()).isEqualTo(CREATED.plusHours(2));
        assertThat(only(institutionA, userA).readAt()).isEqualTo(CREATED.plusMinutes(10));
        assertThat(only(institutionA, userB).readAt()).isEqualTo(CREATED.plusHours(2));
        assertThat(readRecords(id)).isEqualTo(2);
    }

    @Test
    @DisplayName("같은 사용자가 두 번 읽어도 기록은 1건이고 처음 읽은 시각을 돌려준다 (멱등)")
    void sameUserTwice() {
        UUID id = notify(institutionA);
        NotificationReadResponse first = service.markAsRead(institutionA, userA, id);

        clock.set(CREATED.plusHours(5));
        NotificationReadResponse second = service.markAsRead(institutionA, userA, id);

        assertThat(second.readAt()).isEqualTo(first.readAt());
        assertThat(readRecords(id)).isEqualTo(1);
    }

    @Test
    @DisplayName("isRead=true/false 필터는 요청 사용자 기준이다")
    void isReadFilterPerUser() {
        UUID read = notify(institutionA);
        UUID unread = notify(institutionA);
        service.markAsRead(institutionA, userA, read);

        assertThat(list(institutionA, userA, true)).extracting(NotificationResponse::notificationId).containsExactly(read);
        assertThat(list(institutionA, userA, false)).extracting(NotificationResponse::notificationId).containsExactly(unread);
        assertThat(list(institutionA, userB, true)).isEmpty();
        assertThat(list(institutionA, userB, false)).hasSize(2);
    }

    @Test
    @DisplayName("기관 B 사용자는 기관 A 알림을 볼 수 없고, 읽음 처리하면 403이며 읽음 기록이 생기지 않는다")
    void otherInstitutionBlocked() {
        UUID id = notify(institutionA);
        UUID ofB = notify(institutionB);

        assertThat(list(institutionB, userOfB, null)).extracting(NotificationResponse::notificationId).containsExactly(ofB);
        Throwable thrown = catchThrowable(() -> service.markAsRead(institutionB, userOfB, id));

        assertThat(((BaseException) thrown).getErrorCode()).isEqualTo(ErrorCode.NOTIFICATION_ACCESS_DENIED);
        assertThat(readRecords(id)).isZero();
        assertThat(unread(institutionA, userA)).isEqualTo(1);
        // 기관 A 사용자도 기관 B 알림은 읽음 처리할 수 없다
        assertThat(((BaseException) catchThrowable(() -> service.markAsRead(institutionA, userA, ofB))).getErrorCode())
                .isEqualTo(ErrorCode.NOTIFICATION_ACCESS_DENIED);
        assertThat(readRecords(ofB)).isZero();
    }

    @Test
    @DisplayName("없는 알림 읽음 처리는 404이고 읽음 기록이 생기지 않는다")
    void notFound() {
        UUID missing = UUID.randomUUID();

        Throwable thrown = catchThrowable(() -> service.markAsRead(institutionA, userA, missing));

        assertThat(((BaseException) thrown).getErrorCode()).isEqualTo(ErrorCode.NOTIFICATION_NOT_FOUND);
        assertThat(readRecords(missing)).isZero();
    }

    // 테스트에서 현재 시각을 바꾸기 위한 한국 시간 시계
    private static final class MutableClock extends Clock {
        private Instant instant;

        MutableClock(LocalDateTime now) {
            set(now);
        }

        void set(LocalDateTime now) {
            this.instant = now.atZone(ClockConfig.ZONE).toInstant();
        }

        @Override
        public java.time.ZoneId getZone() {
            return ClockConfig.ZONE;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
