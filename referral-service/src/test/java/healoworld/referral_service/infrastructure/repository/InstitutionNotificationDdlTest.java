package healoworld.referral_service.infrastructure.repository;

import healoworld.referral_service.application.service.InstitutionNotificationService;
import healoworld.referral_service.global.config.ClockConfig;
import healoworld.referral_service.global.config.QuerydslConfig;
import healoworld.referral_service.presentation.dto.response.NotificationReadResponse;
import healoworld.referral_service.support.PostgresContainerSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Statement;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 검사기관 공통 알림 테이블의 실제 환경용 DDL(db/manual/institution_notifications.sql)과 동시 저장 중복 방지 검증.
 * <p>
 * - 운영자가 실행할 SQL 파일을 그대로 읽어, 테스트 DB 안의 임시 스키마에 적용한 뒤
 *   ddl-auto가 Entity로 만든 public 테이블과 컬럼·제약·인덱스가 같은지 두 테이블 모두 비교한다 (SQL 파일과 Entity가 어긋나면 실패)
 * - 동시 저장은 실제 커밋이 필요하므로 테스트 트랜잭션을 끄고, 이 테스트가 만든 행만 지운다
 * - 컨텍스트 설정은 NotificationRescheduleUniqueIndexTest와 같게 두어 기존 컨텍스트(연결 풀)를 재사용한다.
 *   서비스도 @Import하지 않고 직접 만든다 (설정이 달라지면 새 컨텍스트가 생겨 DB 연결이 늘어난다)
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(QuerydslConfig.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class InstitutionNotificationDdlTest extends PostgresContainerSupport {

    private static final String DDL = "db/manual/institution_notifications.sql";
    private static final String NOTIFICATIONS = "p_institution_notifications";
    private static final String READS = "p_institution_notification_reads";
    private static final String SCHEMA = "manual_ddl_check";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private InstitutionNotificationRepository repository;

    @Autowired
    private InstitutionNotificationReadRepository readRepository;

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private final List<UUID> institutionIds = new ArrayList<>();
    private final List<UUID> notificationIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (UUID notificationId : notificationIds) {
            jdbcTemplate.update("delete from p_institution_notification_reads where notification_id = ?", notificationId);
        }
        for (UUID institutionId : institutionIds) {
            jdbcTemplate.update("delete from p_institution_notifications where institution_id = ?", institutionId);
        }
        jdbcTemplate.execute("drop schema if exists " + SCHEMA + " cascade");
    }

    @Test
    @DisplayName("수동 DDL로 만든 두 테이블은 Entity(ddl-auto)로 만든 테이블과 컬럼·UNIQUE 제약·인덱스가 같다")
    void manualDdlMatchesEntity() {
        jdbcTemplate.execute((ConnectionCallback<Void>) connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute("create schema " + SCHEMA);
                statement.execute("set search_path to " + SCHEMA);
                for (String sql : statements()) {
                    statement.execute(sql);
                }
            } finally {
                try (Statement reset = connection.createStatement()) {
                    reset.execute("set search_path to public");
                }
            }
            return null;
        });

        assertThat(columns(SCHEMA, NOTIFICATIONS)).isNotEmpty().isEqualTo(columns("public", NOTIFICATIONS))
                .noneMatch(column -> column.startsWith("is_read|") || column.startsWith("read_at|"));
        assertThat(columns(SCHEMA, READS)).isNotEmpty().isEqualTo(columns("public", READS));

        assertThat(constraintColumns(SCHEMA, NOTIFICATIONS, "uk_institution_notifications_institution_reservation_type"))
                .containsExactly("institution_id", "reservation_id", "type")
                .isEqualTo(constraintColumns("public", NOTIFICATIONS,
                        "uk_institution_notifications_institution_reservation_type"));
        assertThat(constraintColumns(SCHEMA, READS, "uk_institution_notification_reads_notification_user"))
                .containsExactly("notification_id", "user_id")
                .isEqualTo(constraintColumns("public", READS, "uk_institution_notification_reads_notification_user"));

        assertThat(indexColumns(SCHEMA, NOTIFICATIONS, "idx_institution_notifications_institution_created"))
                .containsExactly("institution_id", "created_at")
                .isEqualTo(indexColumns("public", NOTIFICATIONS, "idx_institution_notifications_institution_created"));
    }

    @Test
    @DisplayName("같은 기관·예약·유형을 두 트랜잭션이 동시에 저장해도 1건만 남고, 둘 다 예외 없이 끝난다")
    void concurrentInsertKeepsOne() throws Exception {
        UUID institution = UUID.randomUUID();
        institutionIds.add(institution);
        UUID reservation = UUID.randomUUID();
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        List<Integer> inserted = runConcurrently(() -> tx.execute(status -> repository.insertIfAbsent(UUID.randomUUID(),
                institution, UUID.randomUUID(), reservation, UUID.randomUUID(), "RESERVATION_CONFIRMED", "제목", "내용",
                LocalDateTime.of(2026, 11, 10, 9, 0))));

        assertThat(inserted.stream().mapToInt(Integer::intValue).sum()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from p_institution_notifications where institution_id = ?", Long.class, institution))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("같은 사용자가 같은 알림을 동시에 읽음 처리해도 읽음 기록은 1건이고, 두 응답의 readAt은 같은 처음 시각이다")
    void concurrentReadBySameUserKeepsOne() throws Exception {
        UUID institution = UUID.randomUUID();
        institutionIds.add(institution);
        UUID notificationId = UUID.randomUUID();
        notificationIds.add(notificationId);
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> repository.insertIfAbsent(
                notificationId, institution, UUID.randomUUID(), UUID.randomUUID(), null, "RESERVATION_CONFIRMED",
                "제목", "내용", LocalDateTime.of(2026, 11, 10, 9, 0)));
        UUID user = UUID.randomUUID();
        // 서비스의 @Transactional은 프록시가 아니라 직접 만든 객체라 적용되지 않으므로 트랜잭션은 여기서 연다
        InstitutionNotificationService service = new InstitutionNotificationService(repository, readRepository,
                reservationRepository, Clock.system(ClockConfig.ZONE));
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        List<NotificationReadResponse> responses = runConcurrently(() ->
                tx.execute(status -> service.markAsRead(institution, user, notificationId)));

        assertThat(responses).allMatch(NotificationReadResponse::isRead);
        assertThat(responses.get(0).readAt()).isNotNull().isEqualTo(responses.get(1).readAt());
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from p_institution_notification_reads where notification_id = ? and user_id = ?
                """, Long.class, notificationId, user)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                select read_at from p_institution_notification_reads where notification_id = ? and user_id = ?
                """, LocalDateTime.class, notificationId, user)).isEqualTo(responses.get(0).readAt());
    }

    // 같은 작업을 두 스레드에서 동시에 시작해 결과를 모은다
    private static <T> List<T> runConcurrently(Callable<T> task) throws Exception {
        CountDownLatch ready = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(executor.submit(() -> {
                    ready.await();
                    return task.call();
                }));
            }
            ready.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            ready.countDown();
            executor.shutdownNow();
        }
    }

    // 컬럼 이름|타입|길이|정밀도|NULL 허용 (순서와 무관하게 이름순으로 비교)
    private List<String> columns(String schema, String table) {
        return jdbcTemplate.queryForList("""
                select column_name || '|' || data_type || '|' || coalesce(character_maximum_length::text, '-')
                       || '|' || coalesce(datetime_precision::text, '-') || '|' || is_nullable
                  from information_schema.columns
                 where table_schema = ? and table_name = ?
                 order by column_name
                """, String.class, schema, table);
    }

    // 지정한 UNIQUE 제약의 컬럼 (제약 안의 순서대로)
    private List<String> constraintColumns(String schema, String table, String constraint) {
        return jdbcTemplate.queryForList("""
                select a.attname
                  from pg_constraint c
                  join pg_class t on t.oid = c.conrelid
                  join pg_namespace n on n.oid = t.relnamespace
                  cross join lateral unnest(c.conkey) with ordinality as k(attnum, ord)
                  join pg_attribute a on a.attrelid = t.oid and a.attnum = k.attnum
                 where n.nspname = ? and t.relname = ? and c.contype = 'u' and c.conname = ?
                 order by k.ord
                """, String.class, schema, table, constraint);
    }

    private List<String> indexColumns(String schema, String table, String index) {
        return jdbcTemplate.queryForList("""
                select a.attname
                  from pg_index i
                  join pg_class ix on ix.oid = i.indexrelid
                  join pg_class t on t.oid = i.indrelid
                  join pg_namespace n on n.oid = t.relnamespace
                  cross join lateral unnest(i.indkey) with ordinality as k(attnum, ord)
                  join pg_attribute a on a.attrelid = t.oid and a.attnum = k.attnum
                 where n.nspname = ? and t.relname = ? and ix.relname = ?
                 order by k.ord
                """, String.class, schema, table, index);
    }

    private static List<String> statements() {
        String content;
        try {
            content = new ClassPathResource(DDL).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(DDL + "를 읽을 수 없습니다.", e);
        }
        String withoutComments = Arrays.stream(content.split("\\R"))
                .filter(line -> !line.strip().startsWith("--"))
                .reduce("", (a, b) -> a + b + "\n");
        return Arrays.stream(withoutComments.split(";"))
                .map(String::strip)
                .filter(sql -> !sql.isEmpty())
                .toList();
    }
}
