-- 예약 정합성 점검 대상 조회용 부분 인덱스 (p_reservations)
--
-- 이 파일은 애플리케이션이 자동 실행하지 않는다 (ddl-auto·JPA @Index는 WHERE 조건이 있는 부분 인덱스를 만들 수 없다)
-- 운영자가 직접 실행한다. 인덱스만 추가하므로 기존 데이터·동작은 바뀌지 않는다
--
-- 대상 쿼리 (ReservationRepository, ReservationReconciliationScheduler가 1분마다 실행)
-- - idx_reservations_in_progress → findStaleInProgress: 오래 머문 REQUESTED·HELD
--   진행 중 상태는 예약 과정의 잠깐 상태라 전체의 극히 일부다. 그 행만 담고, 쿼리의 커서 순서(id > :lastId ORDER BY id)대로 키를 둔다
-- - idx_reservations_ended_at    → findRecentlyEnded: 최근 lookback-hours 안에 끝난 FAILED·BLOCKED·CANCELED
--   종료 예약은 계속 쌓이므로 id가 아니라 ended_at 범위로 최근 것만 찾는다 (찾은 수백 건은 id로 정렬)
-- - findUpcomingConfirmed는 PK 순서 읽기로 충분해 인덱스를 두지 않는다
-- 쿼리의 상태 조건·deleted_at 조건을 바꾸면 인덱스 조건도 같이 맞춘다 (조건이 어긋나면 플래너가 인덱스를 쓰지 않는다)
--
-- 측정 (기획문서 7절 확대 운영 1년치: 예약 8.8만 건, load-test/sql/reconciliation-*.sql)
-- - findStaleInProgress : 21.6ms → 0.3ms (테이블 전체 읽기 → 인덱스)
-- - findRecentlyEnded   : 17.3ms → 2.1ms
-- - 인덱스 크기: in_progress 16kB, ended_at 304kB
--
-- 주의
-- - DB마다 한 번만 실행한다. 인덱스는 DB에 남으므로 재배포·재시작 때는 다시 실행하지 않는다 (DB를 새로 만들었을 때만 다시 실행)
-- - CREATE INDEX CONCURRENTLY는 예약 쓰기를 막지 않지만, 트랜잭션 블록(BEGIN ... COMMIT) 안에서는 실행할 수 없다. 한 문장씩 autocommit으로 실행한다
-- - 중간에 실패·취소되면 INVALID 인덱스가 남는다. 아래 [확인]으로 보고, INVALID면 [되돌리기]로 지운 뒤 다시 실행한다
-- - IF NOT EXISTS는 일부러 쓰지 않는다. 같은 이름의 INVALID 인덱스가 남아 있어도 성공한 것처럼 끝나기 때문이다

CREATE INDEX CONCURRENTLY idx_reservations_in_progress
    ON p_reservations (id)
    WHERE reservation_status IN ('REQUESTED', 'HELD')
      AND deleted_at IS NULL;

CREATE INDEX CONCURRENTLY idx_reservations_ended_at
    ON p_reservations (ended_at)
    WHERE reservation_status IN ('FAILED', 'BLOCKED', 'CANCELED')
      AND deleted_at IS NULL;

-- [확인] 두 행 모두 indisvalid = true여야 한다
-- SELECT c.relname, i.indisvalid, pg_size_pretty(pg_relation_size(c.oid))
--   FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid
--  WHERE c.relname IN ('idx_reservations_in_progress', 'idx_reservations_ended_at');

-- [되돌리기]
-- DROP INDEX CONCURRENTLY IF EXISTS idx_reservations_in_progress;
-- DROP INDEX CONCURRENTLY IF EXISTS idx_reservations_ended_at;
