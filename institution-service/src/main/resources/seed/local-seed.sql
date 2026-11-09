-- 로컬 개발용 테스트 데이터. UUID는 노션 '인증 헤더 규약'과 동일.
-- 비밀번호는 모두 공개 테스트 값 Test1234! (BCrypt). 운영 계정 아님. 끄려면 .env SEED_ENABLED=never
-- 요양기관기호·주소는 테스트용 가상 값 (운영사는 요양기관기호 없음)

-- p_institutions
INSERT INTO p_institutions (id, name, type, address, care_institution_code, active, created_at, created_by)
VALUES ('20000000-0000-0000-0000-000000000001'::uuid, 'A의원', 'REQUESTER', '서울특별시 강남구 테헤란로 101', '11100001', true,
        CURRENT_TIMESTAMP, '00000000-0000-0000-0000-000000000000'::uuid) ON CONFLICT (id) DO NOTHING;

INSERT INTO p_institutions (id, name, type, address, care_institution_code, active, created_at, created_by)
VALUES ('20000000-0000-0000-0000-000000000002'::uuid, 'D의원', 'REQUESTER', '서울특별시 마포구 월드컵로 202', '11100002', true,
        CURRENT_TIMESTAMP, '00000000-0000-0000-0000-000000000000'::uuid) ON CONFLICT (id) DO NOTHING;

INSERT INTO p_institutions (id, name, type, address, care_institution_code, active, created_at, created_by)
VALUES ('20000000-0000-0000-0000-000000000003'::uuid, 'B영상의학과', 'EXAM', '서울특별시 송파구 올림픽로 303', '11100003', true,
        CURRENT_TIMESTAMP, '00000000-0000-0000-0000-000000000000'::uuid) ON CONFLICT (id) DO NOTHING;

INSERT INTO p_institutions (id, name, type, address, care_institution_code, active, created_at, created_by)
VALUES ('20000000-0000-0000-0000-000000000009'::uuid, 'ITDA운영사', 'PLATFORM', '서울특별시 중구 세종대로 909', NULL, true,
        CURRENT_TIMESTAMP, '00000000-0000-0000-0000-000000000000'::uuid) ON CONFLICT (id) DO NOTHING;

-- p_users
INSERT INTO p_users (id, login_id, password, role, institution_id, active, created_at, created_by)
VALUES ('10000000-0000-0000-0000-000000000001'::uuid, 'clinic_a',
        '$2a$10$DppZq59LanAYWsSqHqfVv.rAozzKkmuZ9Ly3HuI4pBwolHLhmQWP6', 'REQUESTER',
        '20000000-0000-0000-0000-000000000001'::uuid, true,
        CURRENT_TIMESTAMP, '00000000-0000-0000-0000-000000000000'::uuid) ON CONFLICT (id) DO NOTHING;

INSERT INTO p_users (id, login_id, password, role, institution_id, active, created_at, created_by)
VALUES ('10000000-0000-0000-0000-000000000002'::uuid, 'clinic_d',
        '$2a$10$DppZq59LanAYWsSqHqfVv.rAozzKkmuZ9Ly3HuI4pBwolHLhmQWP6', 'REQUESTER',
        '20000000-0000-0000-0000-000000000002'::uuid, true,
        CURRENT_TIMESTAMP, '00000000-0000-0000-0000-000000000000'::uuid) ON CONFLICT (id) DO NOTHING;

INSERT INTO p_users (id, login_id, password, role, institution_id, active, created_at, created_by)
VALUES ('10000000-0000-0000-0000-000000000003'::uuid, 'imaging_b',
        '$2a$10$DppZq59LanAYWsSqHqfVv.rAozzKkmuZ9Ly3HuI4pBwolHLhmQWP6', 'EXAMINER',
        '20000000-0000-0000-0000-000000000003'::uuid, true,
        CURRENT_TIMESTAMP, '00000000-0000-0000-0000-000000000000'::uuid) ON CONFLICT (id) DO NOTHING;

INSERT INTO p_users (id, login_id, password, role, institution_id, active, created_at, created_by)
VALUES ('10000000-0000-0000-0000-000000000009'::uuid, 'admin',
        '$2a$10$DppZq59LanAYWsSqHqfVv.rAozzKkmuZ9Ly3HuI4pBwolHLhmQWP6', 'ADMIN',
        '20000000-0000-0000-0000-000000000009'::uuid, true,
        CURRENT_TIMESTAMP, '00000000-0000-0000-0000-000000000000'::uuid) ON CONFLICT (id) DO NOTHING;

-- p_partnerships: A의원 ↔ B영상의학과 ACTIVE (D의원은 협약 없음 → 기관 격리 테스트용)
INSERT INTO p_partnerships (id, requester_institution_id, exam_institution_id, status,
                            requested_at, approved_at, created_at, created_by)
VALUES ('30000000-0000-0000-0000-000000000001'::uuid,
        '20000000-0000-0000-0000-000000000001'::uuid,
        '20000000-0000-0000-0000-000000000003'::uuid,
        'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP,
        CURRENT_TIMESTAMP, '00000000-0000-0000-0000-000000000000'::uuid) ON CONFLICT (id) DO NOTHING;