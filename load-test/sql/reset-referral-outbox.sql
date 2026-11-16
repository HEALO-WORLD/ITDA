-- referral_db: referral 단독 Outbox 부하 테스트(k6/referral-results.js)용 데이터를 지우고 새로 만든다
-- PENDING 의뢰 + REQUESTED 예약 한 쌍을 예약 수만큼 만든다. k6가 SlotHeld를 넣으면 referral은 예약을 HELD로 바꾸고 Outbox에 BookSlot을 쌓는다
-- 부하 테스트 데이터는 장비 ID f0000000-...-e004로만 구분한다. saga 부하 테스트(e001)·정합성 시드(e002)·다른 데이터는 건드리지 않는다
--
-- ID는 번호로 정한다 (k6 스크립트와 같은 규칙, n을 12자리 16진수로)
--   예약 f2000000-0000-0000-0000-<n>, 의뢰 f3000000-0000-0000-0000-<n>, 슬롯 f1000000-0000-0000-0000-<n>
--   k6가 넣는 SlotHeld의 eventId는 f4000000-0000-0000-0000-<n> (처리 기록 p_consumed_events도 이 ID로 지운다)
-- 예약 수: 기본 10,000개. 늘리려면 psql에 -v reservations=40000을 주고, k6에도 -e RESERVATIONS=40000을 같이 준다
--
-- 실행: docker compose exec -T postgres sh -c 'psql -U "$POSTGRES_USER" -d referral_db -v reservations=40000' < load-test/sql/reset-referral-outbox.sql
\set ON_ERROR_STOP on
\if :{?reservations}
\else
\set reservations 10000
\endif

BEGIN;

-- 의뢰·예약을 지울 때 PostgreSQL은 행마다 참조하는 자식 행이 있는지 FK로 확인한다
-- 참조하는 쪽 컬럼에 인덱스가 없으면 지우는 행마다 자식 테이블 전체를 읽어 수만 건이면 몇 분이 걸린다
-- 이 트랜잭션 안에서만 임시 인덱스를 만들고 커밋 전에 지운다 (reconciliation-cleanup.sql과 같은 방법)
CREATE INDEX tmp_lt_outbox_reservations_referral ON p_reservations (referral_id);
CREATE INDEX tmp_lt_outbox_histories_referral    ON p_referral_histories (referral_id);
CREATE INDEX tmp_lt_outbox_histories_reservation ON p_referral_histories (reservation_id);

CREATE TEMP TABLE lt_reservations ON COMMIT DROP AS
SELECT id, referral_id FROM p_reservations
WHERE equipment_id = 'f0000000-0000-0000-0000-00000000e004';

DELETE FROM p_consumed_events    WHERE id::text LIKE 'f4000000-0000-0000-0000-%';
DELETE FROM p_outbox_events      WHERE aggregate_id IN (SELECT id FROM lt_reservations);
DELETE FROM p_referral_histories WHERE referral_id  IN (SELECT referral_id FROM lt_reservations);
DELETE FROM p_reservations       WHERE id           IN (SELECT id FROM lt_reservations);
DELETE FROM p_referrals          WHERE id           IN (SELECT referral_id FROM lt_reservations);

DROP INDEX tmp_lt_outbox_reservations_referral;
DROP INDEX tmp_lt_outbox_histories_referral;
DROP INDEX tmp_lt_outbox_histories_reservation;

INSERT INTO p_referrals (id, referring_institution_id, referrer_id, exam_type_id, current_reservation_id,
                         referral_status, patient_name, idempotency_key, created_at, updated_at)
SELECT ('f3000000-0000-0000-0000-' || lpad(to_hex(n), 12, '0'))::uuid,
       'f0000000-0000-0000-0000-00000000e004'::uuid,
       'f0000000-0000-0000-0000-00000000e004'::uuid,
       'f0000000-0000-0000-0000-00000000e004'::uuid,
       ('f2000000-0000-0000-0000-' || lpad(to_hex(n), 12, '0'))::uuid,
       'PENDING', '부하테스트' || n, gen_random_uuid(), now(), now()
FROM generate_series(0, :reservations - 1) AS n;

-- 날짜·시간: 2026-12-15부터 하루 20칸, 08:00부터 30분 간격 (reset-slot.sql과 같은 규칙)
INSERT INTO p_reservations (id, referral_id, attempt_no, slot_id, exam_institution_id, equipment_id,
                            exam_date, start_time, end_time, reservation_status, created_at, updated_at)
SELECT ('f2000000-0000-0000-0000-' || lpad(to_hex(n), 12, '0'))::uuid,
       ('f3000000-0000-0000-0000-' || lpad(to_hex(n), 12, '0'))::uuid,
       1,
       ('f1000000-0000-0000-0000-' || lpad(to_hex(n), 12, '0'))::uuid,
       'f0000000-0000-0000-0000-00000000e004'::uuid,
       'f0000000-0000-0000-0000-00000000e004'::uuid,
       DATE '2026-12-15' + (n / 20),
       (DATE '2026-12-15' + (n / 20)) + TIME '08:00' + make_interval(mins => (n % 20) * 30),
       (DATE '2026-12-15' + (n / 20)) + TIME '08:30' + make_interval(mins => (n % 20) * 30),
       'REQUESTED', now(), now()
FROM generate_series(0, :reservations - 1) AS n;

COMMIT;

-- 플래너 통계 갱신 (지우고 넣은 양이 커서, 안 하면 SlotHeld 처리 쿼리가 엉뚱한 실행 계획을 탈 수 있다)
ANALYZE p_referrals;
ANALYZE p_reservations;
ANALYZE p_outbox_events;
ANALYZE p_consumed_events;

SELECT reservation_status, count(*) FROM p_reservations
WHERE equipment_id = 'f0000000-0000-0000-0000-00000000e004' GROUP BY 1;
