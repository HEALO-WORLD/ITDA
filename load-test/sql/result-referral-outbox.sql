-- referral_db: referral 단독 Outbox 부하 테스트(k6/referral-results.js) 결과
-- 워밍업(예약 0~999번)은 빼고 본 측정(1000번~)만 본다
-- 시각 기준
--   created_at:   referral이 SlotHeld를 처리하고 Outbox에 BookSlot을 기록한 시각
--   published_at: Outbox 발행(브로커 확인) 시각
-- referral Outbox에는 원인 이벤트 시각(source_occurred_at)이 없어, k6 전송 → Outbox 기록 구간은 재지 않는다
-- 0번 결과가 0건이 된 뒤(모두 발행된 뒤)에 1~3번을 본다

-- 0) 아직 발행되지 않은 이벤트. 0이 될 때까지 기다린다
SELECT count(*) AS unpublished
FROM p_outbox_events
WHERE aggregate_id::text LIKE 'f2000000-0000-0000-0000-%'
  AND published_at IS NULL;

-- 1) 결과 확인. 본 측정 예약은 보낸 만큼 HELD, 이벤트는 BookSlot만 있어야 정상이다
--    REQUESTED가 남으면 referral이 SlotHeld를 다 처리하지 못한 것이다 (k6 summary의 보낸 수와 비교)
SELECT reservation_status, count(*)
FROM p_reservations
WHERE equipment_id = 'f0000000-0000-0000-0000-00000000e004'
  AND id::text >= 'f2000000-0000-0000-0000-0000000003e8'
GROUP BY 1 ORDER BY 2 DESC;

SELECT event_type, count(*), max(retry_count) AS max_retry
FROM p_outbox_events
WHERE aggregate_id::text LIKE 'f2000000-0000-0000-0000-%'
  AND aggregate_id::text >= 'f2000000-0000-0000-0000-0000000003e8'
GROUP BY 1 ORDER BY 2 DESC;

-- 2) 발행 대기 (초): Outbox 기록 → 발행 완료  ← 개선으로 줄어야 하는 구간 (= outbox.publish.wait)
WITH e AS (
    SELECT event_type,
           extract(epoch FROM published_at - created_at) AS wait_s
    FROM p_outbox_events
    WHERE aggregate_id::text LIKE 'f2000000-0000-0000-0000-%'
      AND aggregate_id::text >= 'f2000000-0000-0000-0000-0000000003e8'
      AND published_at IS NOT NULL
)
SELECT event_type,
       count(*) AS n,
       round(avg(wait_s)::numeric, 3)                                             AS wait_avg,
       round(percentile_cont(0.50) WITHIN GROUP (ORDER BY wait_s)::numeric, 3)    AS wait_p50,
       round(percentile_cont(0.95) WITHIN GROUP (ORDER BY wait_s)::numeric, 3)    AS wait_p95,
       round(percentile_cont(0.99) WITHIN GROUP (ORDER BY wait_s)::numeric, 3)    AS wait_p99,
       round(max(wait_s)::numeric, 3)                                             AS wait_max
FROM e
GROUP BY 1 ORDER BY 1;

-- 3) 10초 구간별 처리량. 들어온 양(recorded)보다 나간 양(published)이 적으면 그 구간부터 밀린다
--    recorded가 k6가 보낸 속도보다 낮으면 Outbox 이전(SlotHeld 수신·처리)에서 막힌 것이다
--    단계 증가(ramp) 측정에서 어느 부하부터 밀리는지 본다
WITH e AS (
    SELECT created_at, published_at,
           extract(epoch FROM published_at - created_at) AS wait_s
    FROM p_outbox_events
    WHERE aggregate_id::text LIKE 'f2000000-0000-0000-0000-%'
      AND aggregate_id::text >= 'f2000000-0000-0000-0000-0000000003e8'
),
bucket AS (   -- 시간대 없는 timestamp 그대로 10초 단위로 자른다
    SELECT date_bin('10 seconds', created_at, TIMESTAMP '2000-01-01') AS t FROM e
    UNION
    SELECT date_bin('10 seconds', published_at, TIMESTAMP '2000-01-01') FROM e WHERE published_at IS NOT NULL
)
SELECT b.t::time AS from_time,
       round((SELECT count(*) FROM e WHERE created_at   >= b.t AND created_at   < b.t + interval '10 s') / 10.0, 1) AS recorded_per_s,
       round((SELECT count(*) FROM e WHERE published_at >= b.t AND published_at < b.t + interval '10 s') / 10.0, 1) AS published_per_s,
       (SELECT round(percentile_cont(0.95) WITHIN GROUP (ORDER BY wait_s)::numeric, 3)
          FROM e WHERE published_at >= b.t AND published_at < b.t + interval '10 s')                                 AS wait_p95_s
FROM bucket b
ORDER BY b.t;
