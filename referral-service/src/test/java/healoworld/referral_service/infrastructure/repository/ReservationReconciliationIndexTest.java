package healoworld.referral_service.infrastructure.repository;

import healoworld.referral_service.global.config.QuerydslConfig;
import healoworld.referral_service.support.PostgresContainerSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 예약 정합성 점검 부분 인덱스(db/manual/reservation_reconciliation_indexes.sql) 검증.
 * <p>
 * - 운영자가 실행할 SQL 파일을 그대로 읽어 격리된 Testcontainers PostgreSQL에 적용한다 (운영용 SQL과 테스트 SQL이 같다)
 * - CREATE INDEX CONCURRENTLY는 트랜잭션 밖에서만 실행되므로 테스트 트랜잭션을 끄고, 문장마다 autocommit으로 실행한다
 * - "인덱스를 쓰는가"는 SQL을 손으로 옮겨 EXPLAIN하지 않고, 실제 repository 메서드를 호출한 뒤
 *   pg_stat_user_indexes.idx_scan이 늘었는지로 본다. JPQL 조건과 인덱스 WHERE 조건이 어긋나면 플래너가 인덱스를 못 써서 실패한다
 * - 행이 적으면 플래너가 테이블 전체 읽기를 고를 수 있어, 조건에 안 맞는 예약을 넉넉히 넣고 ANALYZE한다 (운영과 비슷한 분포)
 * - 테스트 DB는 ddl-auto로 만들어져 이 인덱스가 없으므로 테스트마다 만들고, 끝나면 이 테스트가 만든 행과 인덱스만 지운다
 * - 컨텍스트 설정은 NotificationRescheduleUniqueIndexTest와 같게 두어 기존 컨텍스트를 재사용한다 (DB 연결 수 증가 방지)
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(QuerydslConfig.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ReservationReconciliationIndexTest extends PostgresContainerSupport {

    private static final String APPLY_SQL = "db/manual/reservation_reconciliation_indexes.sql";
    private static final String IN_PROGRESS_INDEX = "idx_reservations_in_progress";
    private static final String ENDED_AT_INDEX = "idx_reservations_ended_at";
    // 이 테스트가 넣은 행을 구분하는 값 (성능 측정 시드 ...e002와도 겹치지 않게)
    private static final UUID MARKER = UUID.fromString("f0000000-0000-0000-0000-00000000e003");
    private static final UUID MIN_ID = new UUID(0L, 0L);
    // 조건에 안 맞는 예약 수. 플래너가 인덱스를 고를 만큼 테이블이 커야 한다
    private static final int FILLER_COUNT = 20_000;
    private static final int STALE_THRESHOLD_MINUTES = 10;
    private static final int LOOKBACK_HOURS = 24;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private final LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
    private UUID referralId;

    @BeforeEach
    void setUp() {
        // 예약은 의뢰를 FK로 참조한다. 예약마다 의뢰를 만들면 정리할 때 FK 확인이 느려져 의뢰 하나를 함께 쓴다
        referralId = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into p_referrals (id, referring_institution_id, referrer_id, exam_type_id, referral_status,
                                         patient_name, idempotency_key, created_at, updated_at)
                values (?, ?, ?, ?, 'PENDING', '인덱스검증', ?, ?, ?)
                """, referralId, MARKER, MARKER, MARKER, UUID.randomUUID(), now, now);
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("delete from p_reservations where equipment_id = ?", MARKER);
        jdbcTemplate.update("delete from p_referrals where id = ?", referralId);
        jdbcTemplate.execute("drop index if exists " + IN_PROGRESS_INDEX);
        jdbcTemplate.execute("drop index if exists " + ENDED_AT_INDEX);
        jdbcTemplate.execute("analyze p_reservations");
    }

    // ---------- 적용 ----------

    @Test
    @DisplayName("1. SQL 파일을 그대로 실행하면 두 부분 인덱스가 VALID로 만들어지고, 정의가 대상 쿼리 조건과 같다")
    void appliesValidPartialIndexes() {
        applyIndexes();

        Map<String, Object> inProgress = indexRow(IN_PROGRESS_INDEX);
        assertThat(inProgress).isNotNull();
        assertThat(inProgress.get("indisvalid")).isEqualTo(true);
        assertThat((String) inProgress.get("definition"))
                .contains("ON public.p_reservations USING btree (id)")
                .contains("'REQUESTED'")
                .contains("'HELD'")
                .contains("deleted_at IS NULL");

        Map<String, Object> endedAt = indexRow(ENDED_AT_INDEX);
        assertThat(endedAt).isNotNull();
        assertThat(endedAt.get("indisvalid")).isEqualTo(true);
        assertThat((String) endedAt.get("definition"))
                .contains("ON public.p_reservations USING btree (ended_at)")
                .contains("'FAILED'")
                .contains("'BLOCKED'")
                .contains("'CANCELED'")
                .contains("deleted_at IS NULL");
    }

    // ---------- 대상 쿼리가 인덱스를 쓰는가 ----------

    @Test
    @DisplayName("2. findStaleInProgress는 idx_reservations_in_progress로 오래 머문 REQUESTED·HELD만 찾는다")
    void staleInProgressUsesIndex() {
        insertFiller();
        UUID staleRequested = insertReservation("REQUESTED", now.plusDays(2), now.minusHours(1), null);
        UUID staleHeld = insertReservation("HELD", now.plusDays(3), now.minusHours(2), null);
        UUID freshRequested = insertReservation("REQUESTED", now.plusDays(2), now, null);   // 방금 바뀜 → 대상 아님
        applyIndexes();
        analyze();

        List<ReconciliationTarget> targets = scanCounting(IN_PROGRESS_INDEX, () ->
                reservationRepository.findStaleInProgress(
                        now.minusMinutes(STALE_THRESHOLD_MINUTES), MIN_ID, PageRequest.of(0, 50)));

        assertThat(targets).extracting(ReconciliationTarget::reservationId)
                .contains(staleRequested, staleHeld)
                .doesNotContain(freshRequested);
    }

    @Test
    @DisplayName("3. findRecentlyEnded는 idx_reservations_ended_at으로 최근 끝난 FAILED·BLOCKED·CANCELED만 찾는다")
    void recentlyEndedUsesIndex() {
        insertFiller();
        UUID recentCanceled = insertReservation("CANCELED", now.plusDays(2), now.minusHours(1), now.minusHours(1));
        UUID recentBlocked = insertReservation("BLOCKED", now.plusDays(4), now.minusHours(3), now.minusHours(3));
        UUID oldCanceled = insertReservation("CANCELED", now.plusDays(2), now.minusDays(3), now.minusDays(3));   // 오래전 종료 → 대상 아님
        applyIndexes();
        analyze();

        List<ReconciliationTarget> targets = scanCounting(ENDED_AT_INDEX, () ->
                reservationRepository.findRecentlyEnded(
                        now, now.minusHours(LOOKBACK_HOURS), MIN_ID, PageRequest.of(0, 50)));

        assertThat(targets).extracting(ReconciliationTarget::reservationId)
                .contains(recentCanceled, recentBlocked)
                .doesNotContain(oldCanceled);
    }

    // ---------- 도우미 ----------

    // 쿼리 실행 전후의 인덱스 사용 횟수를 비교한다.
    // 통계는 보통 늦게 반영되므로 같은 트랜잭션(같은 DB 연결)에서 pg_stat_force_next_flush()를 호출해 커밋 시점에 바로 반영되게 한다
    private <T> T scanCounting(String indexName, Supplier<T> query) {
        long before = idxScan(indexName);

        T result = new TransactionTemplate(transactionManager).execute(status -> {
            jdbcTemplate.execute("select pg_stat_force_next_flush()");
            return query.get();
        });

        assertThat(awaitIdxScanAbove(indexName, before))
                .as("%s를 쓰지 않았다. JPQL 조건과 인덱스 WHERE 조건이 맞는지 확인한다", indexName)
                .isGreaterThan(before);
        return result;
    }

    // 반영이 늦는 환경에 대비해 잠깐 다시 본다 (보통 첫 조회에서 바로 보인다)
    private long awaitIdxScanAbove(String indexName, long before) {
        long current = idxScan(indexName);
        for (int i = 0; i < 20 && current <= before; i++) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            current = idxScan(indexName);
        }
        return current;
    }

    private long idxScan(String indexName) {
        List<Long> rows = jdbcTemplate.queryForList(
                "select coalesce(idx_scan, 0) from pg_stat_user_indexes where indexrelname = ?", Long.class, indexName);
        return rows.isEmpty() ? 0 : rows.getFirst();
    }

    private void applyIndexes() {
        List<String> statements = statements(APPLY_SQL);
        assertThat(statements).as(APPLY_SQL + "에는 CREATE 문장이 두 개 있어야 한다").hasSize(2)
                .allSatisfy(sql -> assertThat(sql).startsWith("CREATE INDEX CONCURRENTLY").doesNotContain("IF NOT EXISTS"));
        statements.forEach(jdbcTemplate::execute);
    }

    private void analyze() {
        jdbcTemplate.execute("analyze p_reservations");
    }

    // 어느 대상 쿼리에도 걸리지 않는 예약: 지난 CONFIRMED, 오래전에 끝난 CANCELED (운영에서 대부분을 차지하는 행)
    private void insertFiller() {
        jdbcTemplate.update("""
                insert into p_reservations (id, referral_id, attempt_no, slot_id, exam_institution_id, equipment_id,
                                            exam_date, start_time, end_time, reservation_status, confirmed_at, ended_at,
                                            created_at, updated_at)
                select gen_random_uuid(), ?, 1, gen_random_uuid(), ?, ?,
                       date_trunc('day', t.start_time), t.start_time, t.start_time + interval '30 minutes',
                       case when g % 2 = 0 then 'CONFIRMED' else 'CANCELED' end,
                       case when g % 2 = 0 then t.start_time - interval '2 days' end,
                       case when g % 2 = 1 then t.start_time - interval '1 day' end,
                       t.start_time - interval '3 days', t.start_time - interval '1 day'
                from generate_series(1, ?) as g
                cross join lateral (select ?::timestamp - interval '7 days' - g * interval '20 minutes' as start_time) t
                """, referralId, MARKER, MARKER, FILLER_COUNT, now);
    }

    private UUID insertReservation(String status, LocalDateTime startTime, LocalDateTime updatedAt, LocalDateTime endedAt) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into p_reservations (id, referral_id, attempt_no, slot_id, exam_institution_id, equipment_id,
                                            exam_date, start_time, end_time, reservation_status, ended_at,
                                            created_at, updated_at)
                values (?, ?, 1, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, referralId, UUID.randomUUID(), MARKER, MARKER,
                startTime.truncatedTo(ChronoUnit.DAYS), startTime, startTime.plusMinutes(30), status, endedAt,
                updatedAt.minusDays(1), updatedAt);
        return id;
    }

    private Map<String, Object> indexRow(String indexName) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select i.indisvalid, pg_get_indexdef(i.indexrelid) as definition
                  from pg_index i join pg_class c on c.oid = i.indexrelid
                 where c.relname = ?
                """, indexName);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    // 운영용 SQL 파일의 실행 문장 (주석 제거)
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
}
