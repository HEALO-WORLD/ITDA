-- slot_db: 부하 테스트 슬롯을 지우고 AVAILABLE 슬롯 10,000개를 새로 만든다
-- 부하 테스트 데이터는 장비 ID f0000000-...-e001로만 구분한다. 다른 데이터는 건드리지 않는다
-- 슬롯 ID는 번호로 정한다: n번 슬롯 = f1000000-0000-0000-0000-<n을 12자리 16진수로>
-- 날짜·시간: 2026-12-15부터 하루 20칸, 08:00부터 30분 간격 (k6 스크립트와 같은 규칙)
BEGIN;

DELETE FROM p_slot_status_histories
WHERE slot_id IN (SELECT id FROM p_slots WHERE equipment_id = 'f0000000-0000-0000-0000-00000000e001');

DELETE FROM p_outbox_events
WHERE aggregate_id IN (SELECT id FROM p_slots WHERE equipment_id = 'f0000000-0000-0000-0000-00000000e001');

DELETE FROM p_slots WHERE equipment_id = 'f0000000-0000-0000-0000-00000000e001';

INSERT INTO p_slots (id, equipment_id, exam_type_id, date, start_time, end_time, status, created_at, updated_at)
SELECT ('f1000000-0000-0000-0000-' || lpad(to_hex(n), 12, '0'))::uuid,
       'f0000000-0000-0000-0000-00000000e001'::uuid,
       'f0000000-0000-0000-0000-00000000a001'::uuid,
       DATE '2026-12-15' + (n / 20),
       TIME '08:00' + make_interval(mins => (n % 20) * 30),
       TIME '08:30' + make_interval(mins => (n % 20) * 30),
       'AVAILABLE', now(), now()
FROM generate_series(0, 9999) AS n;

COMMIT;

SELECT status, count(*) FROM p_slots
WHERE equipment_id = 'f0000000-0000-0000-0000-00000000e001' GROUP BY status;
