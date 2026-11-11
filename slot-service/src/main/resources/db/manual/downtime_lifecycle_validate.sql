-- 01 실행 및 기존 기록 백필 후 slot_db에서 실행한다.
-- 하나라도 미확인 행이 있으면 실패하며, 앱을 시작하지 않는다.
BEGIN;
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM p_equipment_downtimes
               WHERE downtime_type IS NULL OR downtime_type NOT IN ('MAINTENANCE', 'FAILURE')) THEN
        RAISE EXCEPTION '기존 중단의 유형을 확인하고 downtime_type을 먼저 백필하세요.';
END IF;
    IF EXISTS (SELECT 1 FROM p_equipment_downtimes
               WHERE deleted_at IS NULL AND (institution_id IS NULL OR registered_by IS NULL)) THEN
        RAISE EXCEPTION '기존 중단의 장비 소속 기관 및 실제 등록자를 먼저 백필하세요.';
END IF;
    IF EXISTS (SELECT 1 FROM p_equipment_downtimes
               WHERE recovered_at IS NOT NULL
                 AND (recovered_by IS NULL OR recovery_reason IS NULL OR btrim(recovery_reason) = '')) THEN
        RAISE EXCEPTION '복구 확인 기록의 확인자와 사유를 먼저 백필하세요.';
END IF;
END $$;
ALTER TABLE p_equipment_downtimes ALTER COLUMN downtime_type SET NOT NULL;
COMMIT;
