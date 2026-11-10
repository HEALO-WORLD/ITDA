-- 재조정 필요(RESCHEDULE_REQUIRED) 알림: 같은 예약 + 같은 수신자는 최대 1건 (부분 UNIQUE 인덱스)
-- 절차 문서: notification_reschedule_unique.md / 적용 전·후 확인: notification_reschedule_unique_check.sql
--
-- 이 파일은 애플리케이션이 자동 실행하지 않는다 (ddl-auto는 부분 인덱스를 만들 수 없고, spring.sql.init 대상도 아니다)
-- 운영자가 확인 쿼리 [A-1] 결과가 0행인 것을 확인한 뒤 직접 실행한다
--
-- 주의
-- - CREATE INDEX CONCURRENTLY는 트랜잭션 블록(BEGIN ... COMMIT) 안에서 실행할 수 없다. autocommit 상태에서 단독으로 실행한다
-- - 중복 데이터가 있으면 실패하고 INVALID 인덱스가 남는다. 실패 후 그대로 다시 실행하지 말고 절차 문서 [D]를 따른다
-- - IF NOT EXISTS는 일부러 쓰지 않는다. 같은 이름의 INVALID 인덱스가 남아 있어도 성공한 것처럼 끝나기 때문이다
-- - type은 조건으로 고정되므로 인덱스 키에 넣지 않는다. reservation_id가 NULL인 행은 인덱스 대상이 아니다
-- - 이름은 NotificationService 중복 검사(existsByReservationIdAndRecipientUserIdAndType)의 DB 보조 장치로 쓰인다
CREATE UNIQUE INDEX CONCURRENTLY uk_notifications_reservation_recipient_reschedule
    ON p_notifications (reservation_id, recipient_user_id)
    WHERE type = 'RESCHEDULE_REQUIRED'
      AND reservation_id IS NOT NULL;
