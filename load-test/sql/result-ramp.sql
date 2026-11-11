-- referral_db: 시나리오 ② 단계적 증가 결과. 본 측정 시작부터 30초 구간별로 본다
-- 요청 수/초가 단계(LEVELS)를 따라 오르는데 확정 시간이 갑자기 늘어나는 구간이 처리량 한계다
WITH main AS (
    SELECT created_at, confirmed_at, reservation_status
    FROM p_reservations
    WHERE equipment_id = 'f0000000-0000-0000-0000-00000000e001'
      AND slot_id::text >= 'f1000000-0000-0000-0000-0000000003e8'   -- 워밍업 제외
), bucketed AS (
    SELECT floor(extract(epoch FROM created_at - (SELECT min(created_at) FROM main)) / 30)::int AS bucket,
           extract(epoch FROM confirmed_at - created_at) AS e2e,
           reservation_status
    FROM main
)
SELECT bucket * 30                                                                   AS from_s,
       round(count(*) / 30.0, 1)                                                     AS req_per_s,
       count(*) FILTER (WHERE reservation_status <> 'CONFIRMED')                     AS not_confirmed,
       round(percentile_cont(0.50) WITHIN GROUP (ORDER BY e2e)::numeric, 2)          AS p50_s,
       round(percentile_cont(0.95) WITHIN GROUP (ORDER BY e2e)::numeric, 2)          AS p95_s,
       round(max(e2e)::numeric, 2)                                                   AS max_s
FROM bucketed
GROUP BY bucket
ORDER BY bucket;
