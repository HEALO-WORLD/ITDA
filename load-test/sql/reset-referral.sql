-- referral_db: 부하 테스트로 생긴 의뢰·예약·검사·이력·Outbox를 지운다
-- 부하 테스트 장비(f0000000-...-e001)로 만든 예약만 대상이다
BEGIN;

CREATE TEMP TABLE lt_reservations ON COMMIT DROP AS
SELECT id, referral_id FROM p_reservations
WHERE equipment_id = 'f0000000-0000-0000-0000-00000000e001';

DELETE FROM p_outbox_events      WHERE aggregate_id IN (SELECT id FROM lt_reservations);
DELETE FROM p_notifications      WHERE referral_id  IN (SELECT referral_id FROM lt_reservations);
DELETE FROM p_examinations       WHERE referral_id  IN (SELECT referral_id FROM lt_reservations);
DELETE FROM p_referral_histories WHERE referral_id  IN (SELECT referral_id FROM lt_reservations);
DELETE FROM p_reservations       WHERE id           IN (SELECT id FROM lt_reservations);
DELETE FROM p_referrals          WHERE id           IN (SELECT referral_id FROM lt_reservations);

COMMIT;
