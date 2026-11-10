package healoworld.referral_service.infrastructure.repository;

import healoworld.referral_service.domain.entity.InstitutionNotification;
import healoworld.referral_service.presentation.dto.request.NotificationPageRequest;
import healoworld.referral_service.support.RepositoryTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

// 개발 DB와 분리된 Postgres 컨테이너에서 검사기관 공통 알림의 저장·중복 방지와
// 사용자별 읽음 기록 기반 조회(필터·정렬·페이지·건수)·안 읽은 수 쿼리를 검증한다 (테스트마다 롤백)
class InstitutionNotificationRepositoryTest extends RepositoryTestSupport {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 10, 9, 0);

    @Autowired
    private InstitutionNotificationRepository repository;

    @Autowired
    private InstitutionNotificationReadRepository readRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final UUID institution = UUID.randomUUID();
    private final UUID userA = UUID.randomUUID();
    private final UUID userB = UUID.randomUUID();

    private int insert(UUID institutionId, UUID referralId, UUID reservationId, String type, LocalDateTime createdAt) {
        return repository.insertIfAbsent(UUID.randomUUID(), institutionId, referralId, reservationId,
                UUID.randomUUID(), type, "가상 제목", "가상 메시지", createdAt);
    }

    private int insert(UUID institutionId, UUID reservationId, String type) {
        return insert(institutionId, UUID.randomUUID(), reservationId, type, NOW);
    }

    // 생성 시각을 지정해 알림 1건을 만들고 ID를 돌려준다
    private UUID notification(LocalDateTime createdAt) {
        UUID id = UUID.randomUUID();
        repository.insertIfAbsent(id, institution, UUID.randomUUID(), UUID.randomUUID(), null,
                "RESERVATION_CONFIRMED", "제목", "내용", createdAt);
        return id;
    }

    private void read(UUID notificationId, UUID userId, LocalDateTime readAt) {
        readRepository.insertIfAbsent(UUID.randomUUID(), notificationId, userId, readAt);
    }

    private Page<InstitutionNotificationView> search(UUID userId, Boolean isRead, Pageable pageable) {
        return repository.searchForUser(institution, userId, null, isRead, pageable);
    }

    private static List<UUID> ids(Page<InstitutionNotificationView> page) {
        return page.getContent().stream().map(view -> view.notification().getId()).toList();
    }

    private static Pageable page(int page, int size, String... sorts) {
        return NotificationPageRequest.of(String.valueOf(page), String.valueOf(size), sorts.length == 0 ? null : sorts);
    }

    // ---------- 알림 본문 저장·중복 방지 (기존과 같음) ----------

    @Test
    @DisplayName("같은 기관·예약·유형은 두 번째 저장이 예외 없이 0건이고 1건만 남는다")
    void insertIfAbsentSkipsDuplicate() {
        UUID reservation = UUID.randomUUID();

        assertThat(insert(institution, reservation, "RESERVATION_CONFIRMED")).isEqualTo(1);
        assertThat(insert(institution, reservation, "RESERVATION_CONFIRMED")).isZero();

        assertThat(search(userA, null, page(0, 10)).getTotalElements()).isEqualTo(1);
    }

    @Test
    @DisplayName("유형·기관·예약 중 하나라도 다르면 각각 저장된다")
    void differentKeyIsStored() {
        UUID other = UUID.randomUUID();
        UUID reservation = UUID.randomUUID();

        assertThat(insert(institution, reservation, "RESERVATION_CONFIRMED")).isEqualTo(1);
        assertThat(insert(institution, reservation, "RESERVATION_CANCELED")).isEqualTo(1);
        assertThat(insert(institution, UUID.randomUUID(), "RESERVATION_CONFIRMED")).isEqualTo(1);
        assertThat(insert(other, reservation, "RESERVATION_CONFIRMED")).isEqualTo(1);

        assertThat(search(userA, null, page(0, 10)).getTotalElements()).isEqualTo(3);
        assertThat(repository.searchForUser(other, userA, null, null, page(0, 10)).getTotalElements()).isEqualTo(1);
    }

    @Test
    @DisplayName("UNIQUE 제약이 실제로 있어, 일반 INSERT로 같은 키를 넣으면 DB가 거부한다")
    void uniqueConstraintRejectsPlainInsert() {
        UUID reservation = UUID.randomUUID();
        insert(institution, reservation, "RESERVATION_CONFIRMED");

        assertThatThrownBy(() -> jdbcTemplate.update("""
                insert into p_institution_notifications
                       (id, institution_id, referral_id, reservation_id, type, title, message, created_at)
                values (?, ?, ?, ?, 'RESERVATION_CONFIRMED', '제목', '내용', ?)
                """, UUID.randomUUID(), institution, UUID.randomUUID(), reservation, NOW))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("저장된 값: 원인 이벤트·생성 시각 보존, 알림 본문에는 읽음 컬럼이 없다")
    void storedValues() {
        UUID referral = UUID.randomUUID();
        UUID reservation = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        UUID sourceEventId = UUID.randomUUID();
        repository.insertIfAbsent(id, institution, referral, reservation, sourceEventId, "RESERVATION_CONFIRMED",
                "제목", "내용", NOW);

        InstitutionNotification saved = repository.findById(id).orElseThrow();
        assertThat(saved.getInstitutionId()).isEqualTo(institution);
        assertThat(saved.getReferralId()).isEqualTo(referral);
        assertThat(saved.getReservationId()).isEqualTo(reservation);
        assertThat(saved.getSourceEventId()).isEqualTo(sourceEventId);
        assertThat(saved.getCreatedAt()).isEqualTo(NOW);
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from information_schema.columns
                 where table_schema = 'public' and table_name = 'p_institution_notifications'
                   and column_name in ('is_read', 'read_at')
                """, Long.class)).isZero();
    }

    // ---------- 사용자별 읽음 기록 ----------

    @Test
    @DisplayName("같은 사용자의 읽음 기록은 두 번째가 0건이고 처음 읽은 시각이 유지된다. 다른 사용자는 따로 1건")
    void readRecordPerUser() {
        UUID id = notification(NOW);

        assertThat(readRepository.insertIfAbsent(UUID.randomUUID(), id, userA, NOW.plusMinutes(1))).isEqualTo(1);
        assertThat(readRepository.insertIfAbsent(UUID.randomUUID(), id, userA, NOW.plusMinutes(5))).isZero();
        assertThat(readRepository.insertIfAbsent(UUID.randomUUID(), id, userB, NOW.plusMinutes(9))).isEqualTo(1);

        assertThat(readRepository.findReadAt(id, userA)).contains(NOW.plusMinutes(1));
        assertThat(readRepository.findReadAt(id, userB)).contains(NOW.plusMinutes(9));
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from p_institution_notification_reads where notification_id = ?", Long.class, id))
                .isEqualTo(2);
    }

    @Test
    @DisplayName("읽음 기록 UNIQUE(notification_id, user_id) 제약이 실제로 있다")
    void readUniqueConstraint() {
        UUID id = notification(NOW);
        read(id, userA, NOW);

        assertThatThrownBy(() -> jdbcTemplate.update("""
                insert into p_institution_notification_reads (id, notification_id, user_id, read_at)
                values (?, ?, ?, ?)
                """, UUID.randomUUID(), id, userA, NOW))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("목록의 isRead·readAt과 안 읽은 수는 조회한 사용자의 읽음 기록 기준이다")
    void viewAndUnreadCountPerUser() {
        UUID first = notification(NOW);
        UUID second = notification(NOW.plusMinutes(1));
        read(first, userA, NOW.plusMinutes(10));

        assertThat(search(userA, null, page(0, 10)).getContent())
                .extracting(view -> view.notification().getId(), InstitutionNotificationView::readAt)
                .containsExactly(tuple(second, null),
                        tuple(first, NOW.plusMinutes(10)));
        assertThat(search(userB, null, page(0, 10)).getContent()).allMatch(view -> !view.isRead());

        assertThat(repository.countUnreadForUser(institution, userA)).isEqualTo(1);
        assertThat(repository.countUnreadForUser(institution, userB)).isEqualTo(2);
        // 다른 기관 사용자 기준이 아니라 기관 범위로만 센다
        assertThat(repository.countUnreadForUser(UUID.randomUUID(), userA)).isZero();
    }

    @Test
    @DisplayName("isRead=true/false 필터는 사용자 기준이고, 전체 건수·페이지 수도 필터를 반영한다")
    void isReadFilterWithPaging() {
        // 알림 5건 중 사용자 A는 3건 읽음, B는 1건 읽음
        List<UUID> notifications = List.of(notification(NOW), notification(NOW.plusMinutes(1)),
                notification(NOW.plusMinutes(2)), notification(NOW.plusMinutes(3)), notification(NOW.plusMinutes(4)));
        read(notifications.get(0), userA, NOW.plusHours(1));
        read(notifications.get(2), userA, NOW.plusHours(2));
        read(notifications.get(4), userA, NOW.plusHours(3));
        read(notifications.get(1), userB, NOW.plusHours(1));

        Page<InstitutionNotificationView> readByA = search(userA, true, page(0, 10));
        assertThat(ids(readByA)).containsExactly(notifications.get(4), notifications.get(2), notifications.get(0));
        assertThat(readByA.getTotalElements()).isEqualTo(3);

        Page<InstitutionNotificationView> unreadByA = search(userA, false, page(0, 10));
        assertThat(ids(unreadByA)).containsExactly(notifications.get(3), notifications.get(1));
        assertThat(unreadByA.getTotalElements()).isEqualTo(2);
        assertThat(unreadByA.getTotalPages()).isEqualTo(1);

        assertThat(search(userB, true, page(0, 10)).getTotalElements()).isEqualTo(1);
        assertThat(search(userB, false, page(0, 10)).getTotalElements()).isEqualTo(4);
    }

    @Test
    @DisplayName("isRead 필터와 페이지를 함께 쓰면 필터된 전체 결과 기준으로 페이지가 나뉜다 (12건 중 읽음 11건, 크기 10)")
    void isReadFilterAcrossPages() {
        UUID unread = null;
        for (int i = 0; i < 12; i++) {
            UUID id = notification(NOW.plusMinutes(i));
            if (i == 5) {
                unread = id;
            } else {
                read(id, userA, NOW.plusHours(1).plusMinutes(i));
            }
        }

        Page<InstitutionNotificationView> first = search(userA, true, page(0, 10));
        Page<InstitutionNotificationView> second = search(userA, true, page(1, 10));

        assertThat(first.getTotalElements()).isEqualTo(11);
        assertThat(first.getTotalPages()).isEqualTo(2);
        assertThat(first.getContent()).hasSize(10).allMatch(InstitutionNotificationView::isRead);
        assertThat(second.getContent()).hasSize(1).allMatch(InstitutionNotificationView::isRead);
        assertThat(ids(first)).doesNotContain(unread).doesNotContainAnyElementsOf(ids(second));

        Page<InstitutionNotificationView> unreadPage = search(userA, false, page(0, 10));
        assertThat(ids(unreadPage)).containsExactly(unread);
        assertThat(unreadPage.getTotalElements()).isEqualTo(1);
    }

    @Test
    @DisplayName("readAt 정렬은 이 사용자의 읽은 시각 기준이고, 안 읽은 알림은 asc에서 마지막·desc에서 처음이다 (이전 구조와 같은 NULL 순서)")
    void sortByReadAt() {
        UUID a = notification(NOW);
        UUID b = notification(NOW.plusMinutes(1));
        UUID c = notification(NOW.plusMinutes(2));
        UUID unread = notification(NOW.plusMinutes(3));
        read(a, userA, NOW.plusHours(3));
        read(b, userA, NOW.plusHours(1));
        read(c, userA, NOW.plusHours(2));
        // 다른 사용자의 읽은 시각은 A의 정렬에 영향을 주지 않는다
        read(unread, userB, NOW.plusHours(9));
        read(a, userB, NOW.plusMinutes(1));

        assertThat(ids(search(userA, null, page(0, 10, "readAt,asc")))).containsExactly(b, c, a, unread);
        assertThat(ids(search(userA, null, page(0, 10, "readAt,desc")))).containsExactly(unread, a, c, b);
        assertThat(ids(search(userA, true, page(0, 10, "readAt,desc")))).containsExactly(a, c, b);
    }

    @Test
    @DisplayName("readAt 정렬 + 페이지: 페이지 사이에 빠지거나 겹치는 행 없이 이어진다 (같은 readAt은 ID로 순서 고정)")
    void sortByReadAtAcrossPages() {
        List<UUID> all = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            UUID id = notification(NOW.plusMinutes(i));
            all.add(id);
            // 3건씩 같은 읽은 시각, 마지막 3건은 안 읽음
            if (i < 12) {
                read(id, userA, NOW.plusHours(1).plusMinutes(i / 3));
            }
        }

        List<UUID> first = ids(search(userA, null, page(0, 10, "readAt,asc", "createdAt,desc")));
        List<UUID> second = ids(search(userA, null, page(1, 10, "readAt,asc", "createdAt,desc")));

        assertThat(first).hasSize(10);
        assertThat(second).hasSize(5);
        assertThat(first).doesNotContainAnyElementsOf(second);
        List<UUID> merged = new ArrayList<>(first);
        merged.addAll(second);
        assertThat(merged).containsExactlyInAnyOrderElementsOf(all);
        // 안 읽은 3건(readAt NULL)은 asc에서 마지막
        assertThat(second.subList(2, 5)).containsExactlyInAnyOrder(all.get(12), all.get(13), all.get(14));
        // 같은 읽은 시각(첫 3건) 안에서는 createdAt desc
        assertThat(first.subList(0, 3)).containsExactly(all.get(2), all.get(1), all.get(0));
    }

    @Test
    @DisplayName("referralId 필터와 기본 정렬(createdAt desc), 다른 기관 알림 제외")
    void referralFilterAndInstitutionScope() {
        UUID referral = UUID.randomUUID();
        insert(institution, referral, UUID.randomUUID(), "RESERVATION_CONFIRMED", NOW);
        insert(institution, referral, UUID.randomUUID(), "RESERVATION_CANCELED", NOW.plusMinutes(1));
        insert(institution, UUID.randomUUID(), UUID.randomUUID(), "RESERVATION_CONFIRMED", NOW.plusMinutes(2));
        insert(UUID.randomUUID(), referral, UUID.randomUUID(), "RESERVATION_CONFIRMED", NOW);

        Page<InstitutionNotificationView> byReferral = repository.searchForUser(institution, userA, referral, null,
                page(0, 10));

        assertThat(byReferral.getContent()).extracting(view -> view.notification().getType())
                .containsExactly("RESERVATION_CANCELED", "RESERVATION_CONFIRMED");
        assertThat(byReferral.getTotalElements()).isEqualTo(2);
        assertThat(search(userA, null, page(0, 10)).getTotalElements()).isEqualTo(3);
    }
}
