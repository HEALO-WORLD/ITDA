-- institution_db: 부하 테스트용 장비 1대 (B영상의학과 소속)
-- Referral이 의뢰를 만들 때 장비 소유 기관을 Feign으로 확인하므로 장비가 있어야 한다
-- 기관·사용자·협약은 local-seed.sql 데이터(A의원 ↔ B영상의학과 ACTIVE)를 쓴다
INSERT INTO p_equipments (id, institution_id, name, modality, status, manual_status, downtime_revision, created_at, updated_at)
VALUES ('f0000000-0000-0000-0000-00000000e001'::uuid,
        '20000000-0000-0000-0000-000000000003'::uuid,
        'LOADTEST-CT', 'CT', 'OPERATIONAL', 'OPERATIONAL', 0, now(), now())
    ON CONFLICT (id) DO NOTHING;
