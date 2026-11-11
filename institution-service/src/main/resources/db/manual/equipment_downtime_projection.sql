-- institution_db. 앱을 중지한 상태에서 먼저 실행한다.
BEGIN;
ALTER TABLE p_equipments ADD COLUMN IF NOT EXISTS manual_status varchar(20);
ALTER TABLE p_equipments ADD COLUMN IF NOT EXISTS downtime_status varchar(20);
ALTER TABLE p_equipments ADD COLUMN IF NOT EXISTS downtime_revision bigint NOT NULL DEFAULT 0;
-- 기존 수동 점검·고장 상태를 자동으로 해제하지 않기 위해 보존한다.
UPDATE p_equipments SET manual_status = status WHERE manual_status IS NULL;
COMMIT;
