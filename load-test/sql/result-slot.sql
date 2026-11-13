-- slot_db: slot 단독 부하 테스트(k6/slot-commands.js) 결과
-- 워밍업(슬롯 0~999번)은 빼고 본 측정(1000번~)만 본다
-- 시각 기준
--   source_occurred_at: k6가 명령을 만든 시각 (명령의 occurredAt)
--   created_at:         slot이 명령을 처리하고 Outbox에 기록한 시각
--   published_at:       Outbox 발행(브로커 확인) 시각
-- 0번 결과가 0건이 된 뒤(모두 발행된 뒤)에 1~4번을 본다

-- 0) 아직 발행되지 않은 이벤트. 0이 될 때까지 기다린다
SELECT count(*) AS unpublished
FROM p_outbox_events
WHERE aggregate_id::text LIKE 'f1000000-0000-0000-0000-%'
  AND published_at IS NULL;

-- 1) 결과 확인. 슬롯은 모두 BOOKED, 이벤트는 SlotHeld·SlotBooked만 있어야 정상이다
--    SlotBookFailed가 있으면 HoldRequested·BookSlot 순서가 깨진 것이다 (파티션 키 확인)
SELECT status, count(*)
FROM p_slots
WHERE equipment_id = 'f0000000-0000-0000-0000-00000000e001'
  AND id::text >= 'f1000000-0000-0000-0000-0000000003e8'
GROUP BY 1 ORDER BY 2 DESC;

SELECT event_type, count(*)
FROM p_outbox_events
WHERE aggregate_id::text LIKE 'f1000000-0000-0000-0000-%'
  AND aggregate_id::text >= 'f1000000-0000-0000-0000-0000000003e8'
GROUP BY 1 ORDER BY 2 DESC;

-- 2) 이벤트별 지연 (초)
--    처리:      명령 생성 → Outbox 기록 (Kafka 전달 + slot 처리)
--    발행 대기: Outbox 기록 → 발행 완료  ← 2번 개선으로 줄어야 하는 구간
--    전체:      명령 생성 → 발행 완료 (= slot.event.dwell)
WITH e AS (
    SELECT event_type,
           extract(epoch FROM created_at - source_occurred_at)   AS process_s,
           extract(epoch FROM published_at - created_at)         AS wait_s,
           extract(epoch FROM published_at - source_occurred_at) AS dwell_s
    FROM p_outbox_events
    WHERE aggregate_id::text LIKE 'f1000000-0000-0000-0000-%'
      AND aggregate_id::text >= 'f1000000-0000-0000-0000-0000000003e8'
      AND published_at IS NOT NULL
)
SELECT event_type,
       count(*) AS n,
       round(percentile_cont(0.50) WITHIN GROUP (ORDER BY process_s)::numeric, 3) AS process_p50,
       round(percentile_cont(0.95) WITHIN GROUP (ORDER BY process_s)::numeric, 3) AS process_p95,
       round(percentile_cont(0.50) WITHIN GROUP (ORDER BY wait_s)::numeric, 3)    AS wait_p50,
       round(percentile_cont(0.95) WITHIN GROUP (ORDER BY wait_s)::numeric, 3)    AS wait_p95,
       round(percentile_cont(0.99) WITHIN GROUP (ORDER BY wait_s)::numeric, 3)    AS wait_p99,
       round(max(wait_s)::numeric, 3)                                             AS wait_max,
       round(percentile_cont(0.50) WITHIN GROUP (ORDER BY dwell_s)::numeric, 3)   AS dwell_p50,
       round(percentile_cont(0.95) WITHIN GROUP (ORDER BY dwell_s)::numeric, 3)   AS dwell_p95,
       round(percentile_cont(0.99) WITHIN GROUP (ORDER BY dwell_s)::numeric, 3)   AS dwell_p99,
       round(max(dwell_s)::numeric, 3)                                            AS dwell_max
FROM e
GROUP BY 1 ORDER BY 1;

-- 3) 예약 1건 기준: HoldRequested 생성 → SlotBooked 발행 완료 (초)
WITH r AS (
    SELECT aggregate_id,
           extract(epoch FROM max(published_at) FILTER (WHERE event_type = 'SlotBooked')
                            - min(source_occurred_at) FILTER (WHERE event_type = 'SlotHeld')) AS total_s
    FROM p_outbox_events
    WHERE aggregate_id::text LIKE 'f1000000-0000-0000-0000-%'
      AND aggregate_id::text >= 'f1000000-0000-0000-0000-0000000003e8'
    GROUP BY aggregate_id
)
SELECT count(total_s) AS reservations,
       round(avg(total_s)::numeric, 3)                                            AS avg_s,
       round(percentile_cont(0.50) WITHIN GROUP (ORDER BY total_s)::numeric, 3)   AS p50_s,
       round(percentile_cont(0.95) WITHIN GROUP (ORDER BY total_s)::numeric, 3)   AS p95_s,
       round(percentile_cont(0.99) WITHIN GROUP (ORDER BY total_s)::numeric, 3)   AS p99_s,
       round(max(total_s)::numeric, 3)                                            AS max_s
FROM r;

-- 4) 10초 구간별 처리량. 들어온 양(recorded)보다 나간 양(published)이 적으면 그 구간부터 밀린다
--    단계 증가(ramp) 측정에서 어느 부하부터 밀리는지 본다
WITH e AS (
    SELECT created_at, published_at,
           extract(epoch FROM published_at - source_occurred_at) AS dwell_s
    FROM p_outbox_events
    WHERE aggregate_id::text LIKE 'f1000000-0000-0000-0000-%'
      AND aggregate_id::text >= 'f1000000-0000-0000-0000-0000000003e8'
),
bucket AS (   -- 시간대 없는 timestamp 그대로 10초 단위로 자른다
    SELECT date_bin('10 seconds', created_at, TIMESTAMP '2000-01-01') AS t FROM e
    UNION
    SELECT date_bin('10 seconds', published_at, TIMESTAMP '2000-01-01') FROM e WHERE published_at IS NOT NULL
)
SELECT b.t::time AS from_time,
       round((SELECT count(*) FROM e WHERE created_at   >= b.t AND created_at   < b.t + interval '10 s') / 10.0, 1) AS recorded_per_s,
       round((SELECT count(*) FROM e WHERE published_at >= b.t AND published_at < b.t + interval '10 s') / 10.0, 1) AS published_per_s,
       (SELECT round(percentile_cont(0.95) WITHIN GROUP (ORDER BY dwell_s)::numeric, 3)
          FROM e WHERE published_at >= b.t AND published_at < b.t + interval '10 s')                                 AS dwell_p95_s
FROM bucket b
ORDER BY b.t;
