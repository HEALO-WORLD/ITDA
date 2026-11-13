-- referral_db: reconciliation-seed.sql로 넣은 성능 측정용 데이터를 지운다
-- 전용 장비 ID(f0000000-...-e002)로 만든 예약과 거기에 딸린 의뢰·Outbox·정합성 실패 기록만 대상이다
--
-- 실행: docker exec -i itda-postgres psql -U itda -d referral_db < load-test/sql/reconciliation-cleanup.sql

\set ON_ERROR_STOP on

BEGIN;

-- 예약·의뢰를 지울 때 PostgreSQL은 행마다 "이 행을 참조하는 자식 행이 있는가"를 FK로 확인한다
-- 참조하는 쪽 컬럼(referral_id·reservation_id)에 인덱스가 없으면 지우는 행마다 자식 테이블을 처음부터 끝까지 읽는다
-- (확대 1년 시드 8.8만 건 기준 약 12분). 이 트랜잭션 안에서만 임시 인덱스를 만들고 커밋 전에 지운다
-- 트랜잭션이 실패하면 인덱스 생성도 함께 롤백된다
CREATE INDEX tmp_perf_cleanup_reservations_referral ON p_reservations (referral_id);
CREATE INDEX tmp_perf_cleanup_histories_referral    ON p_referral_histories (referral_id);
CREATE INDEX tmp_perf_cleanup_histories_reservation ON p_referral_histories (reservation_id);

CREATE TEMP TABLE perf_reservations ON COMMIT DROP AS
SELECT id, referral_id FROM p_reservations
WHERE equipment_id = 'f0000000-0000-0000-0000-00000000e002';

-- 스케줄러가 시드 데이터에 대해 만든 Outbox·실패 기록도 함께 지운다
DELETE FROM p_outbox_events           WHERE aggregate_id   IN (SELECT id FROM perf_reservations);
DELETE FROM p_reconciliation_failures WHERE reservation_id IN (SELECT id FROM perf_reservations);
DELETE FROM p_reservations            WHERE id             IN (SELECT id FROM perf_reservations);
DELETE FROM p_referrals               WHERE id             IN (SELECT referral_id FROM perf_reservations);

SELECT count(*) AS deleted_reservations FROM perf_reservations;

DROP INDEX tmp_perf_cleanup_reservations_referral;
DROP INDEX tmp_perf_cleanup_histories_referral;
DROP INDEX tmp_perf_cleanup_histories_reservation;

COMMIT;

ANALYZE p_referrals;
ANALYZE p_reservations;
ANALYZE p_outbox_events;
ANALYZE p_reconciliation_failures;
