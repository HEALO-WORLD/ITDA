package healoworld.referral_service.infrastructure.repository;

import healoworld.referral_service.global.config.QuerydslConfig;
import healoworld.referral_service.support.PostgresContainerSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 재조정 알림 부분 UNIQUE 인덱스(db/manual/notification_reschedule_unique.sql) 검증.
 * <p>
 * - 운영자가 실행할 SQL 파일을 그대로 읽어 격리된 Testcontainers PostgreSQL에 적용한다 (운영용 SQL과 테스트 SQL이 같다)
 * - CREATE INDEX CONCURRENTLY는 트랜잭션 밖에서만 실행되므로 테스트 트랜잭션을 끄고, 문장마다 autocommit으로 실행한다
 *   (UNIQUE 위반으로 한 문장이 실패해도 다음 문장은 별도 트랜잭션이라 오류 상태가 이어지지 않는다)
 * - 테스트 DB는 ddl-auto로 만들어져 이 인덱스가 없으므로 테스트마다 만들고, 끝나면 이 테스트가 만든 행과 인덱스만 지운다
 * - 컨텍스트 설정은 ExaminationRepositoryTest와 같게 두어 기존 컨텍스트를 재사용한다 (DB 연결 수 증가 방지)
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(QuerydslConfig.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class NotificationRescheduleUniqueIndexTest extends PostgresContainerSupport {

    private static final String INDEX_NAME = "uk_notifications_reservation_recipient_reschedule";
    private static final String APPLY_SQL = "db/manual/notification_reschedule_unique.sql";
    private static final String CHECK_SQL = "db/manual/notification_reschedule_unique_check.sql";
    private static final String RESCHEDULE_REQUIRED = "RESCHEDULE_REQUIRED";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<UUID> insertedIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (UUID id : insertedIds) {
            jdbcTemplate.update("delete from p_notifications where id = ?", id);
        }
        jdbcTemplate.execute("drop index if exists " + INDEX_NAME);
    }

    // ---------- 제약 동작 ----------

    @Test
    @DisplayName("1. 같은 예약 + 같은 수신자의 두 번째 RESCHEDULE_REQUIRED는 UNIQUE 위반(23505)으로 거부된다")
    void rejectsSecondForSameReservationAndRecipient() {
        applyIndex();
        UUID reservationId = UUID.randomUUID();
        UUID recipient = UUID.randomUUID();
        insert(reservationId, recipient, RESCHEDULE_REQUIRED, false);

        assertUniqueViolation(() -> insert(reservationId, recipient, RESCHEDULE_REQUIRED, false));
        assertThat(count(reservationId, recipient, RESCHEDULE_REQUIRED)).isEqualTo(1);
    }

    @Test
    @DisplayName("2. 같은 예약이라도 수신자가 다르면 허용된다")
    void allowsDifferentRecipient() {
        applyIndex();
        UUID reservationId = UUID.randomUUID();
        insert(reservationId, UUID.randomUUID(), RESCHEDULE_REQUIRED, false);
        insert(reservationId, UUID.randomUUID(), RESCHEDULE_REQUIRED, false);

        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from p_notifications where reservation_id = ?", Long.class, reservationId)).isEqualTo(2);
    }

    @Test
    @DisplayName("3. 같은 수신자라도 예약이 다르면(재예약으로 새 예약) 허용된다")
    void allowsDifferentReservation() {
        applyIndex();
        UUID recipient = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        insert(first, recipient, RESCHEDULE_REQUIRED, false);
        insert(second, recipient, RESCHEDULE_REQUIRED, false);

        assertThat(count(first, recipient, RESCHEDULE_REQUIRED)).isEqualTo(1);
        assertThat(count(second, recipient, RESCHEDULE_REQUIRED)).isEqualTo(1);
    }

    @Test
    @DisplayName("4. 다른 알림 유형은 같은 예약·수신자로 여러 건이어도 이 인덱스의 영향을 받지 않는다")
    void otherTypesAreNotRestricted() {
        applyIndex();
        UUID reservationId = UUID.randomUUID();
        UUID recipient = UUID.randomUUID();
        insert(reservationId, recipient, RESCHEDULE_REQUIRED, false);
        insert(reservationId, recipient, "RESERVATION_CANCELLED", false);
        insert(reservationId, recipient, "RESERVATION_CANCELLED", false);

        assertThat(count(reservationId, recipient, "RESERVATION_CANCELLED")).isEqualTo(2);
    }

    @Test
    @DisplayName("5. 기존 알림을 읽었어도 같은 예약·수신자의 두 번째 RESCHEDULE_REQUIRED는 거부된다")
    void rejectsEvenIfExistingIsRead() {
        applyIndex();
        UUID reservationId = UUID.randomUUID();
        UUID recipient = UUID.randomUUID();
        insert(reservationId, recipient, RESCHEDULE_REQUIRED, true);

        assertUniqueViolation(() -> insert(reservationId, recipient, RESCHEDULE_REQUIRED, false));
    }

    @Test
    @DisplayName("6. reservation_id가 NULL인 RESCHEDULE_REQUIRED는 인덱스 대상이 아니라 같은 수신자로 여러 건 저장된다")
    void nullReservationIsExcluded() {
        applyIndex();
        UUID recipient = UUID.randomUUID();
        insert(null, recipient, RESCHEDULE_REQUIRED, false);
        insert(null, recipient, RESCHEDULE_REQUIRED, false);

        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from p_notifications
                 where reservation_id is null and recipient_user_id = ? and type = ?
                """, Long.class, recipient, RESCHEDULE_REQUIRED)).isEqualTo(2);
    }

    // ---------- 적용 절차 (확인 쿼리·실패 대응) ----------

    @Test
    @DisplayName("C. 적용 후 확인 쿼리로 인덱스가 VALID·READY·UNIQUE이고 정의가 기대한 컬럼·조건인지 확인할 수 있다")
    void postCheckShowsValidIndexDefinition() {
        runCheckQueries();   // 적용 전: 확인 쿼리가 모두 실행되고 인덱스는 없다
        assertThat(indexRow()).isNull();

        applyIndex();

        List<List<Map<String, Object>>> results = runCheckQueries();
        Map<String, Object> index = indexRow();
        assertThat(index).isNotNull();
        assertThat(index.get("indisunique")).isEqualTo(true);
        assertThat(index.get("indisvalid")).isEqualTo(true);
        assertThat(index.get("indisready")).isEqualTo(true);
        assertThat((String) index.get("definition"))
                .contains("UNIQUE INDEX " + INDEX_NAME)
                .contains("(reservation_id, recipient_user_id)")
                .contains("'RESCHEDULE_REQUIRED'")
                .contains("reservation_id IS NOT NULL");
        // [C] 결과 목록에도 같은 인덱스가 보인다
        assertThat(results.getLast()).anySatisfy(row -> assertThat(row.get("index_name")).isEqualTo(INDEX_NAME));
    }

    @Test
    @DisplayName("A-1. 중복이 있으면 확인 쿼리가 찾아내고, D. 그 상태에서 적용하면 실패하고 INVALID 인덱스가 남는다")
    void duplicateDataLeavesInvalidIndex() {
        UUID reservationId = UUID.randomUUID();
        UUID recipient = UUID.randomUUID();
        insert(reservationId, recipient, RESCHEDULE_REQUIRED, false);
        insert(reservationId, recipient, RESCHEDULE_REQUIRED, true);   // 인덱스 없이 만들어진 기존 중복

        List<Map<String, Object>> duplicates = runCheckQueries().getFirst();
        assertThat(duplicates).anySatisfy(row -> {
            assertThat(row.get("reservation_id")).isEqualTo(reservationId);
            assertThat(((Number) row.get("duplicate_count")).longValue()).isEqualTo(2);
        });

        assertUniqueViolation(this::applyIndex);
        Map<String, Object> index = indexRow();
        assertThat(index).as("CONCURRENTLY 실패 후 이름이 남는다").isNotNull();
        assertThat(index.get("indisvalid")).isEqualTo(false);
        assertThat(index.get("indisready")).as("빌드 스캔에서 실패해 쓰기에 쓰이지 않는 상태").isEqualTo(false);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from p_notifications where reservation_id = ?", Long.class, reservationId))
                .as("인덱스 생성 실패는 기존 데이터를 지우지 않는다").isEqualTo(2);

        // 이 상태(indisready = false)의 INVALID 인덱스는 새 중복도 막지 않는다 → 적용되지 않은 것으로 봐야 한다
        UUID otherReservation = UUID.randomUUID();
        insert(otherReservation, recipient, RESCHEDULE_REQUIRED, false);
        insert(otherReservation, recipient, RESCHEDULE_REQUIRED, false);
        assertThat(count(otherReservation, recipient, RESCHEDULE_REQUIRED)).isEqualTo(2);
    }

    @Test
    @DisplayName("D. IF NOT EXISTS로 다시 실행하면 INVALID 인덱스가 남아 있어도 오류 없이 끝나므로 상태 확인이 필요하다")
    void ifNotExistsHidesInvalidIndex() {
        UUID reservationId = UUID.randomUUID();
        UUID recipient = UUID.randomUUID();
        insert(reservationId, recipient, RESCHEDULE_REQUIRED, false);
        insert(reservationId, recipient, RESCHEDULE_REQUIRED, false);
        assertUniqueViolation(this::applyIndex);

        String ifNotExists = applyStatement().replace("CONCURRENTLY " + INDEX_NAME, "CONCURRENTLY IF NOT EXISTS " + INDEX_NAME);
        assertThat(ifNotExists).contains("IF NOT EXISTS");
        jdbcTemplate.execute(ifNotExists);   // 예외 없이 끝난다

        assertThat(indexRow().get("indisvalid")).as("여전히 INVALID").isEqualTo(false);
    }

    // ---------- 도우미 ----------

    private void applyIndex() {
        jdbcTemplate.execute(applyStatement());
    }

    // 운영용 SQL 파일의 CREATE 문 (주석 제거). 파일에는 문장이 하나만 있어야 한다
    private String applyStatement() {
        List<String> statements = statements(APPLY_SQL);
        assertThat(statements).as(APPLY_SQL + "에는 실행 문장이 하나만 있어야 한다").hasSize(1);
        assertThat(statements.getFirst()).startsWith("CREATE UNIQUE INDEX CONCURRENTLY " + INDEX_NAME)
                .doesNotContain("IF NOT EXISTS");
        return statements.getFirst();
    }

    // 확인 SQL 파일의 SELECT를 차례로 실행한 결과 ([A-1], [A-2], [A-3]/[C])
    private List<List<Map<String, Object>>> runCheckQueries() {
        List<String> statements = statements(CHECK_SQL);
        assertThat(statements).hasSize(3).allSatisfy(sql -> assertThat(sql).startsWith("SELECT"));
        return statements.stream().map(jdbcTemplate::queryForList).toList();
    }

    private Map<String, Object> indexRow() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select i.indisunique, i.indisvalid, i.indisready, i.indislive, pg_get_indexdef(i.indexrelid) as definition
                  from pg_index i join pg_class c on c.oid = i.indexrelid
                 where c.relname = ?
                """, INDEX_NAME);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private static List<String> statements(String classpathLocation) {
        String content;
        try {
            content = new ClassPathResource(classpathLocation).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(classpathLocation + "를 읽을 수 없습니다.", e);
        }
        String withoutComments = Arrays.stream(content.split("\\R"))
                .filter(line -> !line.strip().startsWith("--"))
                .reduce("", (a, b) -> a + b + "\n");
        return Arrays.stream(withoutComments.split(";"))
                .map(String::strip)
                .filter(sql -> !sql.isEmpty())
                .toList();
    }

    private void insert(UUID reservationId, UUID recipient, String type, boolean read) {
        UUID id = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update("""
                insert into p_notifications (id, referral_id, reservation_id, incident_referral_id, recipient_user_id,
                                             type, title, message, is_read, created_at, read_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, UUID.randomUUID(), reservationId, UUID.randomUUID(), recipient,
                type, "가상 알림", "테스트용 가상 알림입니다.", read, now, read ? now : null);
        insertedIds.add(id);
    }

    private long count(UUID reservationId, UUID recipient, String type) {
        Long count = jdbcTemplate.queryForObject("""
                select count(*) from p_notifications
                 where reservation_id = ? and recipient_user_id = ? and type = ?
                """, Long.class, reservationId, recipient, type);
        return count == null ? 0 : count;
    }

    private static void assertUniqueViolation(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOf(DataAccessException.class)
                .satisfies(e -> {
                    assertThat(NestedExceptionUtils.getMostSpecificCause(e)).isInstanceOf(SQLException.class);
                    assertThat(((SQLException) NestedExceptionUtils.getMostSpecificCause(e)).getSQLState()).isEqualTo("23505");
                    assertThat(e.getMessage()).contains(INDEX_NAME);
                });
    }
}
