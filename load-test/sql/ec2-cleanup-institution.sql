-- institution_db (EC2): 부하 테스트 기관·계정·협약·장비를 지운다
-- slot_db·referral_db의 테스트 데이터는 reset-slot.sql, reset-referral.sql로 먼저 지운다
-- (reset-slot.sql은 슬롯을 다시 만들므로, 마지막에는 그 뒤에 slot_db에서 DELETE FROM p_slots WHERE equipment_id = 'f0000000-0000-0000-0000-00000000e001'까지 실행)
BEGIN;

DELETE FROM p_users        WHERE institution_id IN ('f0000000-0000-0000-0000-0000000000a1', 'f0000000-0000-0000-0000-0000000000b1');
DELETE FROM p_partnership_histories WHERE partnership_id = 'f0000000-0000-0000-0000-0000000000c1';
DELETE FROM p_partnerships WHERE id = 'f0000000-0000-0000-0000-0000000000c1';
DELETE FROM p_equipments   WHERE id = 'f0000000-0000-0000-0000-00000000e001';
DELETE FROM p_institutions WHERE id IN ('f0000000-0000-0000-0000-0000000000a1', 'f0000000-0000-0000-0000-0000000000b1');

COMMIT;
