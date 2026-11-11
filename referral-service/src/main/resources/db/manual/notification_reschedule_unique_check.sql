-- 재조정 필요(RESCHEDULE_REQUIRED) 알림 부분 UNIQUE 인덱스: 적용 전·후 확인 쿼리 (읽기 전용)
-- 절차 문서: notification_reschedule_unique.md
-- 이 파일은 SELECT만 담는다. 애플리케이션이 자동 실행하지 않으며, 운영자가 psql 등으로 직접 실행한다.

-- [A-1] 기존 중복 확인: 결과가 1행이라도 있으면 인덱스를 만들지 않는다 (생성이 실패하고 INVALID 인덱스가 남는다)
--       중복 데이터는 자동으로 지우지 않는다. 어떤 행을 남길지는 운영자가 판단한다
SELECT reservation_id,
       recipient_user_id,
       count(*)                          AS duplicate_count,
       count(*) FILTER (WHERE is_read)   AS read_count,
       min(created_at)                   AS first_created_at,
       max(created_at)                   AS last_created_at
  FROM p_notifications
 WHERE type = 'RESCHEDULE_REQUIRED'
   AND reservation_id IS NOT NULL
 GROUP BY reservation_id, recipient_user_id
HAVING count(*) > 1
 ORDER BY duplicate_count DESC, reservation_id;

-- [A-2] 인덱스 대상에서 빠지는 행: 예약 ID가 없는 재조정 알림 (이 행들은 인덱스가 중복을 막지 않는다)
SELECT count(*) AS reschedule_without_reservation
  FROM p_notifications
 WHERE type = 'RESCHEDULE_REQUIRED'
   AND reservation_id IS NULL;

-- [A-3] / [C] 알림 테이블의 인덱스 전체와 상태
--       적용 전: uk_notifications_reservation_recipient_reschedule 이름의 인덱스가 없어야 한다
--               같은 컬럼·조건의 다른 이름 인덱스가 이미 있는지도 함께 본다 (중복 생성 방지)
--       적용 후: 해당 인덱스가 indisvalid = true, indisready = true, indisunique = true이고
--               definition이 아래 기대 정의와 같아야 한다
--               CREATE UNIQUE INDEX uk_notifications_reservation_recipient_reschedule ON public.p_notifications
--               USING btree (reservation_id, recipient_user_id)
--               WHERE (((type)::text = 'RESCHEDULE_REQUIRED'::text) AND (reservation_id IS NOT NULL))
SELECT ic.relname                   AS index_name,
       i.indisunique,
       i.indisvalid,
       i.indisready,
       i.indislive,
       pg_get_indexdef(i.indexrelid) AS definition
  FROM pg_index i
  JOIN pg_class ic     ON ic.oid = i.indexrelid
  JOIN pg_class tc     ON tc.oid = i.indrelid
  JOIN pg_namespace ns ON ns.oid = tc.relnamespace
 WHERE tc.relname = 'p_notifications'
   AND ns.nspname = current_schema()
 ORDER BY ic.relname;
