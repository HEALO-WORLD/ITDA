-- referral_db: 측정 결과. 워밍업(슬롯 0~999번)은 빼고 본 측정(1000번~)만 본다
-- saga 전체 시간 = 예약 생성(created_at) → 확정(confirmed_at). 이벤트 4번(Outbox 발행 4번)을 거친다

-- 1) 예약 상태별 건수. 모두 CONFIRMED여야 정상이다
SELECT reservation_status, failure_reason, count(*)
FROM p_reservations
WHERE equipment_id = 'f0000000-0000-0000-0000-00000000e001'
  AND slot_id::text >= 'f1000000-0000-0000-0000-0000000003e8'
GROUP BY 1, 2 ORDER BY 3 DESC;

-- 2) 요청 → 확정까지 걸린 시간 (초)
SELECT count(*)                                                                               AS confirmed,
       round(avg(extract(epoch FROM confirmed_at - created_at))::numeric, 3)                  AS avg_s,
       round(percentile_cont(0.50) WITHIN GROUP (ORDER BY extract(epoch FROM confirmed_at - created_at))::numeric, 3) AS p50_s,
       round(percentile_cont(0.95) WITHIN GROUP (ORDER BY extract(epoch FROM confirmed_at - created_at))::numeric, 3) AS p95_s,
       round(percentile_cont(0.99) WITHIN GROUP (ORDER BY extract(epoch FROM confirmed_at - created_at))::numeric, 3) AS p99_s,
       round(max(extract(epoch FROM confirmed_at - created_at))::numeric, 3)                  AS max_s
FROM p_reservations
WHERE equipment_id = 'f0000000-0000-0000-0000-00000000e001'
  AND slot_id::text >= 'f1000000-0000-0000-0000-0000000003e8'
  AND reservation_status = 'CONFIRMED';
