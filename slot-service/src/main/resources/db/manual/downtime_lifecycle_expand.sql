-- slot_db. 앱을 중지한 상태에서 실행. 실제 DB에는 자동 실행하지 않는다.
BEGIN;
ALTER TABLE p_equipment_downtimes ADD COLUMN IF NOT EXISTS downtime_type varchar(20);
ALTER TABLE p_equipment_downtimes ADD COLUMN IF NOT EXISTS institution_id uuid;
ALTER TABLE p_equipment_downtimes ADD COLUMN IF NOT EXISTS registered_by uuid;
ALTER TABLE p_equipment_downtimes ADD COLUMN IF NOT EXISTS activated_at timestamp;
ALTER TABLE p_equipment_downtimes ADD COLUMN IF NOT EXISTS recovered_at timestamp;
ALTER TABLE p_equipment_downtimes ADD COLUMN IF NOT EXISTS recovered_by uuid;
ALTER TABLE p_equipment_downtimes ADD COLUMN IF NOT EXISTS recovery_reason varchar(500);
CREATE TABLE IF NOT EXISTS p_equipment_downtime_revisions (
                                                              equipment_id uuid PRIMARY KEY,
                                                              revision bigint NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_downtime_due
    ON p_equipment_downtimes(started_at, equipment_id)
    WHERE deleted_at IS NULL AND recovered_at IS NULL AND activated_at IS NULL;
COMMIT;

-- 기존 행을 확인한다. 종료 예정 시각이 지났다는 이유만으로 recovered_at을 채우지 않는다.
SELECT id, equipment_id, started_at, ended_at, reason, downtime_type,
       institution_id, registered_by, recovered_at
FROM p_equipment_downtimes
ORDER BY started_at;

-- 확인한 행마다 수행할 백필 예시(실제 UUID와 유형·복구 사실로 바꿔서 실행):
-- UPDATE p_equipment_downtimes
-- SET downtime_type = 'MAINTENANCE', institution_id = '<실제 장비 소속 기관 UUID>',
--     registered_by = '<실제 등록자 UUID>'
-- WHERE id = '<중단 UUID>';
-- 이미 복구한 사실이 확인된 경우에만 recovered_at(KST), recovered_by, recovery_reason도 채운다.
