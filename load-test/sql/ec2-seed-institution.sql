-- institution_db (EC2): 부하 테스트 전용 기관 2곳, 협약, 장비
-- EC2에는 local-seed.sql이 없어서 테스트 데이터를 따로 만든다. ID는 f0000000-...로 구분해 실제 데이터와 섞이지 않는다
-- 계정은 비밀번호를 BCrypt로 저장해야 해서 회원가입 API로 만들고 ec2-approve-user.sql로 승인한다
-- 측정이 끝나면 ec2-cleanup-institution.sql로 지운다
BEGIN;

INSERT INTO p_institutions (id, name, type, address, care_institution_code, active, created_at, updated_at)
VALUES ('f0000000-0000-0000-0000-0000000000a1'::uuid, 'LOADTEST Clinic', 'REQUESTER', 'loadtest', '99999901', true, now(), now()),
       ('f0000000-0000-0000-0000-0000000000b1'::uuid, 'LOADTEST Imaging', 'EXAM', 'loadtest', '99999902', true, now(), now())
ON CONFLICT (id) DO NOTHING;

INSERT INTO p_partnerships (id, requester_institution_id, exam_institution_id, status,
                            requested_at, approved_at, created_at, updated_at)
VALUES ('f0000000-0000-0000-0000-0000000000c1'::uuid,
        'f0000000-0000-0000-0000-0000000000a1'::uuid,
        'f0000000-0000-0000-0000-0000000000b1'::uuid,
        'ACTIVE', now(), now(), now(), now())
ON CONFLICT (id) DO NOTHING;

INSERT INTO p_equipments (id, institution_id, name, modality, status, manual_status, downtime_revision, created_at, updated_at)
VALUES ('f0000000-0000-0000-0000-00000000e001'::uuid,
        'f0000000-0000-0000-0000-0000000000b1'::uuid,
        'LOADTEST-CT', 'CT', 'OPERATIONAL', 'OPERATIONAL', 0, now(), now())
ON CONFLICT (id) DO NOTHING;

COMMIT;
