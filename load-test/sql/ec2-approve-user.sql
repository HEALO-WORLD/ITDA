-- institution_db (EC2): 회원가입 API로 만든 부하 테스트 계정을 승인한다
UPDATE p_users
SET active = true, updated_at = now()
WHERE login_id = 'loadtest_requester'
  AND institution_id = 'f0000000-0000-0000-0000-0000000000a1';

SELECT login_id, role, active FROM p_users WHERE login_id = 'loadtest_requester';
