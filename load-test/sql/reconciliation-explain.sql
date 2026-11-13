-- referral_db: 예약 정합성 점검 대상 조회 쿼리 3개의 실행 계획
--
-- 실행: docker exec -i itda-postgres psql -U itda -d referral_db < load-test/sql/reconciliation-explain.sql > before-explain.txt
-- 먼저 reconciliation-seed.sql로 데이터를 넣는다
--
-- 쿼리는 ReservationRepository의 JPQL(findStaleInProgress·findUpcomingConfirmed·findRecentlyEnded)을
-- Hibernate가 만드는 SQL과 같은 형태로 옮겼다. JPQL을 바꾸면 여기도 맞춘다
-- (확인: JPA_SHOW_SQL=true로 앱을 띄우면 실제 SQL이 로그에 찍힌다)
--
-- 볼 것
--   - Seq Scan on p_reservations / p_outbox_events : 인덱스 없이 테이블 전체를 읽음
--   - Rows Removed by Filter                       : 읽고 버린 행 수 (클수록 낭비)
--   - Buffers: shared hit/read                     : 읽은 페이지 수 (hit = 메모리, read = 디스크)
--   - Execution Time                               : 기준 수치
-- 커서가 처음일 때(최소 UUID)와 중간일 때(8000... 이후) 두 번씩 본다. 스케줄러는 회차마다 커서를 옮기며 훑는다
-- 같은 쿼리를 연달아 돌리면 두 번째는 캐시 덕에 빨라진다. 비교할 때는 개선 전후 모두 같은 순서로 실행한 결과를 쓴다

\set ON_ERROR_STOP on
\pset pager off

-- 스케줄러 설정값 (application.yml)
\set batch_size 50
\set stale_threshold_minutes 10
\set lookback_hours 24
\set cursor_first '00000000-0000-0000-0000-000000000000'
\set cursor_mid   '80000000-0000-0000-0000-000000000000'

-- 기준 시각: 앱(ClockConfig)과 같은 한국 시간
SELECT (now() AT TIME ZONE 'Asia/Seoul')::timestamp(6) AS now \gset

-- 측정 시점 데이터 양
SELECT 'p_reservations' AS table_name, count(*) AS rows FROM p_reservations
UNION ALL SELECT 'p_outbox_events', count(*) FROM p_outbox_events
UNION ALL SELECT 'p_outbox_events (미발행)', count(*) FROM p_outbox_events WHERE published_at IS NULL
UNION ALL SELECT 'p_reconciliation_failures', count(*) FROM p_reconciliation_failures;

-- 현재 인덱스 (개선 전후 비교용)
SELECT tablename, indexdef FROM pg_indexes
WHERE tablename IN ('p_reservations', 'p_outbox_events', 'p_reconciliation_failures')
ORDER BY tablename, indexname;

-- ========== 1. findStaleInProgress: 오래 머문 REQUESTED·HELD ==========
\echo '===== 1. findStaleInProgress (cursor = first) ====='
EXPLAIN (ANALYZE, BUFFERS)
SELECT r.id, r.slot_id, r.reservation_status, r.failure_reason, r.confirmed_at
FROM p_reservations r
WHERE r.reservation_status IN ('REQUESTED', 'HELD')
  AND r.updated_at < :'now'::timestamp - make_interval(mins => :stale_threshold_minutes)
  AND r.id > :'cursor_first'::uuid
  AND r.deleted_at IS NULL
  AND NOT EXISTS (SELECT 1 FROM p_outbox_events o WHERE o.aggregate_id = r.id AND o.published_at IS NULL)
  AND NOT EXISTS (SELECT 1 FROM p_reconciliation_failures f WHERE f.reservation_id = r.id AND f.status = 'MANUAL_REVIEW')
ORDER BY r.id
FETCH FIRST :batch_size ROWS ONLY;

\echo '===== 1. findStaleInProgress (cursor = mid) ====='
EXPLAIN (ANALYZE, BUFFERS)
SELECT r.id, r.slot_id, r.reservation_status, r.failure_reason, r.confirmed_at
FROM p_reservations r
WHERE r.reservation_status IN ('REQUESTED', 'HELD')
  AND r.updated_at < :'now'::timestamp - make_interval(mins => :stale_threshold_minutes)
  AND r.id > :'cursor_mid'::uuid
  AND r.deleted_at IS NULL
  AND NOT EXISTS (SELECT 1 FROM p_outbox_events o WHERE o.aggregate_id = r.id AND o.published_at IS NULL)
  AND NOT EXISTS (SELECT 1 FROM p_reconciliation_failures f WHERE f.reservation_id = r.id AND f.status = 'MANUAL_REVIEW')
ORDER BY r.id
FETCH FIRST :batch_size ROWS ONLY;

-- ========== 2. findUpcomingConfirmed: 미래 CONFIRMED ==========
\echo '===== 2. findUpcomingConfirmed (cursor = first) ====='
EXPLAIN (ANALYZE, BUFFERS)
SELECT r.id, r.slot_id, r.reservation_status, r.failure_reason, r.confirmed_at
FROM p_reservations r
WHERE r.reservation_status = 'CONFIRMED'
  AND r.start_time > :'now'::timestamp
  AND r.id > :'cursor_first'::uuid
  AND r.deleted_at IS NULL
  AND NOT EXISTS (SELECT 1 FROM p_outbox_events o WHERE o.aggregate_id = r.id AND o.published_at IS NULL)
  AND NOT EXISTS (SELECT 1 FROM p_reconciliation_failures f WHERE f.reservation_id = r.id AND f.status = 'MANUAL_REVIEW')
ORDER BY r.id
FETCH FIRST :batch_size ROWS ONLY;

\echo '===== 2. findUpcomingConfirmed (cursor = mid) ====='
EXPLAIN (ANALYZE, BUFFERS)
SELECT r.id, r.slot_id, r.reservation_status, r.failure_reason, r.confirmed_at
FROM p_reservations r
WHERE r.reservation_status = 'CONFIRMED'
  AND r.start_time > :'now'::timestamp
  AND r.id > :'cursor_mid'::uuid
  AND r.deleted_at IS NULL
  AND NOT EXISTS (SELECT 1 FROM p_outbox_events o WHERE o.aggregate_id = r.id AND o.published_at IS NULL)
  AND NOT EXISTS (SELECT 1 FROM p_reconciliation_failures f WHERE f.reservation_id = r.id AND f.status = 'MANUAL_REVIEW')
ORDER BY r.id
FETCH FIRST :batch_size ROWS ONLY;

-- ========== 3. findRecentlyEnded: 최근 끝난 CANCELED·FAILED·BLOCKED (슬롯은 미래) ==========
\echo '===== 3. findRecentlyEnded (cursor = first) ====='
EXPLAIN (ANALYZE, BUFFERS)
SELECT r.id, r.slot_id, r.reservation_status, r.failure_reason, r.confirmed_at
FROM p_reservations r
WHERE r.reservation_status IN ('FAILED', 'BLOCKED', 'CANCELED')
  AND r.ended_at > :'now'::timestamp - make_interval(hours => :lookback_hours)
  AND r.id > :'cursor_first'::uuid
  AND r.start_time > :'now'::timestamp
  AND r.deleted_at IS NULL
  AND NOT EXISTS (SELECT 1 FROM p_outbox_events o WHERE o.aggregate_id = r.id AND o.published_at IS NULL)
  AND NOT EXISTS (SELECT 1 FROM p_reconciliation_failures f WHERE f.reservation_id = r.id AND f.status = 'MANUAL_REVIEW')
ORDER BY r.id
FETCH FIRST :batch_size ROWS ONLY;

\echo '===== 3. findRecentlyEnded (cursor = mid) ====='
EXPLAIN (ANALYZE, BUFFERS)
SELECT r.id, r.slot_id, r.reservation_status, r.failure_reason, r.confirmed_at
FROM p_reservations r
WHERE r.reservation_status IN ('FAILED', 'BLOCKED', 'CANCELED')
  AND r.ended_at > :'now'::timestamp - make_interval(hours => :lookback_hours)
  AND r.id > :'cursor_mid'::uuid
  AND r.start_time > :'now'::timestamp
  AND r.deleted_at IS NULL
  AND NOT EXISTS (SELECT 1 FROM p_outbox_events o WHERE o.aggregate_id = r.id AND o.published_at IS NULL)
  AND NOT EXISTS (SELECT 1 FROM p_reconciliation_failures f WHERE f.reservation_id = r.id AND f.status = 'MANUAL_REVIEW')
ORDER BY r.id
FETCH FIRST :batch_size ROWS ONLY;
