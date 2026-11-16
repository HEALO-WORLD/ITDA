-- referral_db: 예약 정합성 점검 스케줄러 성능 측정용 대량 데이터
--
-- 실행: docker exec -i itda-postgres psql -U itda -d referral_db < load-test/sql/reconciliation-seed.sql
-- 정리: reconciliation-cleanup.sql
--
-- 데이터 양은 기획문서 7절 "서비스 도입 규모 가정"을 따른다
--   예약 수 = 하루 의뢰 수 × 운영 일수 (의뢰 1건당 예약 1건, 예약 1건당 Outbox 4건 = saga 이벤트 4번)
--   발행 완료 Outbox는 지우는 정책이 없으므로 운영 기간만큼 계속 쌓인다
--
--   | 시나리오     | daily_referrals | days | 예약    | Outbox  |
--   | ① 중규모 1년 | 60              | 365  | 약 2.2만 | 약 8.8만 |
--   | ② 확대 1년   | 240             | 365  | 약 8.8만 | 약 35만  |  ← 기본값
--   | ③ 확대 3년   | 240             | 1095 | 약 26만  | 약 105만 |
--
-- 다른 시나리오는 아래 \set 값을 바꾸거나 실행 시 -v daily_referrals=60 처럼 넘긴다 (cleanup 후 다시 실행)
-- 시드 데이터는 전용 장비 ID(...e002)로 구분한다. 부하 테스트(...e001)·실제 데이터와 섞이지 않는다
-- slot_id는 slot-service에 없는 값이라, 스케줄러는 "슬롯 정보 없음"으로 건너뛴다 (조회 쿼리 3개 측정이 목적)

\set ON_ERROR_STOP on

-- ---------- 시나리오 ----------
\if :{?daily_referrals}
\else
  \set daily_referrals 240
\endif
\if :{?days}
\else
  \set days 365
\endif
-- 확정 예약은 최대 며칠 앞까지 잡히는가 (findUpcomingConfirmed 대상)
\set booking_ahead_days 14
-- 오래 머문 REQUESTED·HELD (findStaleInProgress 대상). 정상 운영이면 드물다
\set stale_in_progress 20
-- 아직 발행되지 않은 Outbox가 남은 예약 수 (점검 제외 조건)
\set unpublished_outbox 100
-- MANUAL_REVIEW로 점검에서 빠진 예약 수 (점검 제외 조건)
\set manual_review 50
-- 지난 예약 중 취소·실패·차단으로 끝난 비율 (%)
\set past_ended_pct 15

\set marker 'f0000000-0000-0000-0000-00000000e002'
\set tz 'Asia/Seoul'

-- 이미 시드돼 있으면 중복으로 넣지 않는다
SELECT EXISTS (SELECT 1 FROM p_reservations WHERE equipment_id = :'marker') AS already_seeded \gset
\if :already_seeded
  \echo '이미 시드 데이터가 있습니다. reconciliation-cleanup.sql을 먼저 실행하세요.'
  \quit
\endif

BEGIN;

-- 기준 시각: 앱(ClockConfig)과 같은 한국 시간
CREATE TEMP TABLE seed_now ON COMMIT DROP AS
SELECT (now() AT TIME ZONE :'tz')::timestamp(6) AS now;

-- 예약 한 건당 한 행. kind로 점검 쿼리별 대상을 나눈다
--   upcoming_confirmed : 미래 CONFIRMED                       → findUpcomingConfirmed
--   recently_ended     : 최근 24시간 안에 끝났고 슬롯은 미래 → findRecentlyEnded
--   stale              : updated_at이 오래된 REQUESTED·HELD   → findStaleInProgress
--   past_confirmed     : 지난 CONFIRMED (어느 쿼리에도 안 걸리고 필터로 버려지는 행)
--   past_ended         : 오래전에 끝난 CANCELED·FAILED·BLOCKED (마찬가지)
CREATE TEMP TABLE seed_reservations ON COMMIT DROP AS
WITH params AS (
    SELECT :daily_referrals * :days                    AS total,
           :daily_referrals * :booking_ahead_days      AS upcoming,
           :daily_referrals                            AS recent,
           :stale_in_progress                          AS stale
)
SELECT gen_random_uuid()                                AS id,
       gen_random_uuid()                                AS referral_id,
       gen_random_uuid()                                AS slot_id,
       g.i,
       CASE
           WHEN g.i <= p.upcoming                                   THEN 'upcoming_confirmed'
           WHEN g.i <= p.upcoming + p.recent                        THEN 'recently_ended'
           WHEN g.i <= p.upcoming + p.recent + p.stale              THEN 'stale'
           WHEN random() * 100 < :past_ended_pct                    THEN 'past_ended'
           ELSE 'past_confirmed'
       END                                              AS kind,
       random()                                         AS r
FROM params p
CROSS JOIN generate_series(1, p.total) AS g(i);

-- 시각·상태 계산
CREATE TEMP TABLE seed_rows ON COMMIT DROP AS
SELECT s.*,
       CASE s.kind
           WHEN 'upcoming_confirmed' THEN n.now + interval '1 hour' + s.r * (:booking_ahead_days * interval '1 day')
           WHEN 'recently_ended'     THEN n.now + interval '1 hour' + s.r * (:booking_ahead_days * interval '1 day')
           WHEN 'stale'              THEN n.now + interval '1 day'  + s.r * interval '7 days'
           ELSE                           n.now - interval '1 day'  - s.r * (:days * interval '1 day')
       END                                              AS start_time,
       CASE s.kind
           WHEN 'upcoming_confirmed' THEN 'CONFIRMED'
           WHEN 'past_confirmed'     THEN 'CONFIRMED'
           WHEN 'stale'              THEN (ARRAY['REQUESTED', 'HELD'])[1 + (s.i % 2)]
           ELSE                           (ARRAY['CANCELED', 'FAILED', 'BLOCKED'])[1 + (s.i % 3)]
       END                                              AS status,
       n.now
FROM seed_reservations s
CROSS JOIN seed_now n;

INSERT INTO p_referrals (id, referring_institution_id, referrer_id, exam_type_id, current_reservation_id,
                         referral_status, patient_name, idempotency_key, created_at, updated_at)
SELECT referral_id, :'marker', :'marker', :'marker', id,
       CASE status WHEN 'CONFIRMED' THEN 'CONFIRMED'
                   WHEN 'CANCELED'  THEN 'CANCELED'
                   WHEN 'FAILED'    THEN 'FAILED'
                   WHEN 'BLOCKED'   THEN 'NEEDS_RESCHEDULE'
                   ELSE 'PENDING' END,
       '성능측정' || i, gen_random_uuid(),
       LEAST(start_time, now) - interval '2 days', LEAST(start_time, now) - interval '2 days'
FROM seed_rows;

INSERT INTO p_reservations (id, referral_id, attempt_no, slot_id, exam_institution_id, equipment_id,
                            exam_date, start_time, end_time, reservation_status, failure_reason,
                            confirmed_at, ended_at, created_at, updated_at)
SELECT id, referral_id, 1, slot_id, :'marker', :'marker',
       date_trunc('day', start_time), start_time, start_time + interval '30 minutes', status,
       CASE status WHEN 'FAILED' THEN 'SLOT_CONFLICT' WHEN 'BLOCKED' THEN 'EQUIPMENT_FAILURE' END,
       CASE WHEN status = 'CONFIRMED' THEN LEAST(start_time, now) - interval '2 days' END,
       CASE kind
           WHEN 'recently_ended' THEN now - r * interval '12 hours'
           WHEN 'past_ended'     THEN start_time - interval '1 day'
       END,
       LEAST(start_time, now) - interval '2 days',
       CASE kind
           WHEN 'stale'          THEN now - interval '1 hour' - r * interval '1 day'
           WHEN 'recently_ended' THEN now - r * interval '12 hours'
           ELSE                       LEAST(start_time, now) - interval '1 day'
       END
FROM seed_rows;

-- 예약당 Outbox 4건 (요청 → 홀드 → 확정 등 saga 이벤트). 기본은 모두 발행 완료
INSERT INTO p_outbox_events (id, aggregate_type, aggregate_id, topic, message_key, event_type, payload,
                             retry_count, published_at, created_at, updated_at)
SELECT gen_random_uuid(), 'RESERVATION', s.id, 'reservation-perf-seed', s.id::text,
       (ARRAY['ReservationRequested', 'SlotHeld', 'ReservationConfirmRequested', 'SlotBooked'])[e.n],
       '{}'::json, 0,
       s.created_at + e.n * interval '1 second',
       s.created_at + e.n * interval '1 second',
       s.created_at + e.n * interval '1 second'
FROM (SELECT id, LEAST(start_time, now) - interval '2 days' AS created_at FROM seed_rows) s
CROSS JOIN generate_series(1, 4) AS e(n);

-- 미발행 Outbox가 남은 예약 (점검 대상 쿼리에서 제외되는 조건)
UPDATE p_outbox_events o
   SET published_at = NULL
  FROM (SELECT id FROM seed_rows WHERE kind IN ('upcoming_confirmed', 'stale', 'recently_ended')
        ORDER BY r LIMIT :unpublished_outbox) u
 WHERE o.aggregate_id = u.id
   AND o.event_type = 'SlotBooked';

-- MANUAL_REVIEW로 점검에서 빠진 예약 (점검 대상 쿼리에서 제외되는 조건)
INSERT INTO p_reconciliation_failures (id, reservation_id, result_type, status, failure_count,
                                       error_code, failure_reason, first_failed_at, last_failed_at)
SELECT gen_random_uuid(), id, 'SLOTS_BLOCKED', 'MANUAL_REVIEW', 3,
       'PERF_SEED', '성능 측정용 시드 데이터', now, now
FROM seed_rows
WHERE kind = 'upcoming_confirmed'
ORDER BY r DESC
LIMIT :manual_review;

-- 시드 결과 요약
SELECT kind, status, count(*) FROM seed_rows GROUP BY 1, 2 ORDER BY 1, 2;

COMMIT;

-- 플래너 통계 갱신 (안 하면 실행 계획이 실제 데이터 분포를 모른다)
ANALYZE p_referrals;
ANALYZE p_reservations;
ANALYZE p_outbox_events;
ANALYZE p_reconciliation_failures;

SELECT 'p_reservations' AS table_name, count(*) AS rows, pg_size_pretty(pg_total_relation_size('p_reservations')) AS size FROM p_reservations
UNION ALL
SELECT 'p_outbox_events', count(*), pg_size_pretty(pg_total_relation_size('p_outbox_events')) FROM p_outbox_events
UNION ALL
SELECT 'p_reconciliation_failures', count(*), pg_size_pretty(pg_total_relation_size('p_reconciliation_failures')) FROM p_reconciliation_failures;
