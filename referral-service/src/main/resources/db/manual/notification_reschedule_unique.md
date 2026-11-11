# 재조정 알림 부분 UNIQUE 인덱스 적용 절차

| 항목 | 내용 |
|---|---|
| 대상 DB | referral-service DB (`referral_db`), 테이블 `p_notifications` |
| 인덱스 | `uk_notifications_reservation_recipient_reschedule` |
| 적용 SQL | `notification_reschedule_unique.sql` (CREATE 1문장) |
| 확인 SQL | `notification_reschedule_unique_check.sql` (SELECT 3문장, 읽기 전용) |
| 자동 실행 | 하지 않는다. `ddl-auto: update`는 부분 인덱스를 만들 수 없고, 이 폴더는 `spring.sql.init` 대상이 아니다 |
| 검증 | `NotificationRescheduleUniqueIndexTest`가 두 SQL 파일을 그대로 읽어 Testcontainers PostgreSQL에서 실행한다 |

## 목적과 정책

같은 예약 + 같은 수신자에게 재조정 필요(`RESCHEDULE_REQUIRED`) 알림은 최대 1건만 저장한다.

- 지금은 `NotificationService.createRescheduleRequired()`가 저장 전에 같은 작업·같은 예약의 알림을 확인하고,
  호출하는 장애 후속 처리(`IncidentProcessingService`)가 의뢰 행을 잠근 트랜잭션에서 실행해 중복을 막는다.
- 이 인덱스는 앞으로 잠금 없이 알림을 만드는 경로가 생겨도 DB가 최종적으로 막도록 하는 보조 장치다.

| 경우 | 결과 |
|---|---|
| 같은 예약 + 같은 수신자 + `RESCHEDULE_REQUIRED` 두 번째 | 거부 (SQLState 23505) |
| 기존 알림을 읽었어도 (`is_read = true`) | 거부 (읽음 여부는 조건에 없음) |
| 같은 예약 + 다른 수신자 | 허용 |
| 다른 예약(재예약으로 생긴 새 예약) + 같은 수신자 | 허용 |
| 다른 알림 유형 | 영향 없음 (인덱스 조건 밖) |
| `reservation_id`가 NULL인 `RESCHEDULE_REQUIRED` | 인덱스 대상이 아님 → DB가 중복을 막지 않는다 |

인덱스 키에 `type`을 넣지 않는 이유: 조건(`WHERE type = 'RESCHEDULE_REQUIRED'`)으로 이미 고정되므로 키에 넣어도 구분에 도움이 되지 않고 인덱스만 커진다.

## A. 적용 전 확인

**[A-0] 스키마 확인 (먼저 실행).** 확인 SQL의 [A-3]은 `current_schema()`(search_path의 첫 번째 스키마)만 보고,
적용 SQL은 테이블 이름을 스키마 없이 쓰므로 search_path에서 찾은 `p_notifications`에 인덱스를 만든다.
두 기준이 같은 스키마를 가리키는지 접속한 세션에서 먼저 확인한다 (읽기 전용).

```sql
SHOW search_path;
SELECT current_schema();

-- 대상 테이블이 어느 스키마에 있는지 (정확히 1행이어야 한다)
SELECT n.nspname AS schema_name, c.relname
  FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
 WHERE c.relname = 'p_notifications' AND c.relkind IN ('r', 'p');

-- 같은 이름의 relation이 이미 있는지 (스키마·테이블과 관계없이 전체에서 찾는다. 0행이어야 한다)
SELECT n.nspname AS schema_name, c.relname, c.relkind
  FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
 WHERE c.relname = 'uk_notifications_reservation_recipient_reschedule';
```

- 테이블 스키마가 `current_schema()`와 다르거나 2행 이상이면 진행하지 않는다.
  세션에서만 `SET search_path TO <대상 스키마>;`로 맞춘 뒤 A-0부터 다시 확인한다 (DB·사용자 기본 설정은 바꾸지 않는다).
- 같은 이름의 relation이 있으면(다른 테이블의 인덱스 포함) B를 실행하지 않는다. 같은 스키마에 있으면 CREATE가 "이미 존재" 오류로 끝나고,
  [A-3]은 `p_notifications`의 인덱스만 보여 주므로 다른 테이블에 있는 같은 이름은 이 쿼리로만 보인다.

그다음 `notification_reschedule_unique_check.sql`을 같은 세션에서 실행한다 (SELECT만 있어 파일 전체를 실행해도 안전하다).

1. **[A-1] 기존 중복**: 결과가 **0행**이어야 한다.
   - 1행 이상이면 적용하지 않는다. 생성이 실패하고 INVALID 인덱스가 남는다.
   - 중복 데이터는 자동으로 지우지 않는다. 어떤 알림을 남길지(읽음 여부, 생성 시각)는 운영자가 판단하고, 정리는 별도 승인 후 진행한다.
2. **[A-2] 예약 ID 없는 재조정 알림 수**: 인덱스가 막지 못하는 행이다. 0이 아니면 원인을 확인한다 (현재 코드는 항상 예약 ID를 채운다).
3. **[A-3] 인덱스 목록**:
   - `uk_notifications_reservation_recipient_reschedule`이 **없어야** 한다. 있으면 B를 실행하지 말고 C로 상태를 확인한다.
   - 같은 컬럼·조건의 다른 이름 인덱스가 있는지도 본다 (있으면 새로 만들지 않는다).

## B. 적용

`notification_reschedule_unique.sql`을 실행한다.

```sql
CREATE UNIQUE INDEX CONCURRENTLY uk_notifications_reservation_recipient_reschedule
    ON p_notifications (reservation_id, recipient_user_id)
    WHERE type = 'RESCHEDULE_REQUIRED'
      AND reservation_id IS NOT NULL;
```

- **트랜잭션 블록 밖에서 실행한다.** `CREATE INDEX CONCURRENTLY`는 `BEGIN ... COMMIT` 안에서 실행할 수 없다.
  psql은 기본 autocommit이다. GUI 도구는 자동 트랜잭션 모드를 끈다.
- `CONCURRENTLY`는 테이블 쓰기를 막지 않는 대신 테이블을 두 번 읽으므로 시간이 더 걸린다. 진행 중인 오래된 트랜잭션이 있으면 끝날 때까지 기다린다.
- **`IF NOT EXISTS`를 쓰지 않는다.** 같은 이름의 INVALID 인덱스가 남아 있으면 경고(NOTICE)만 내고 성공한 것처럼 끝난다
  (`NotificationRescheduleUniqueIndexTest`에서 확인). 이름 존재 여부는 A-3에서 직접 확인한다.

## C. 적용 후 확인

확인 SQL의 [A-3]/[C] 쿼리를 다시 실행해 아래를 모두 확인한다.

| 항목 | 기대값 |
|---|---|
| `indisunique` | `true` |
| `indisvalid` | `true` (false면 INVALID → D) |
| `indisready` | `true` |
| `indislive` | `true` (false면 삭제가 진행 중인 인덱스라 곧 사라진다 → 적용된 것으로 보지 않는다) |
| `definition` | `CREATE UNIQUE INDEX uk_notifications_reservation_recipient_reschedule ON public.p_notifications USING btree (reservation_id, recipient_user_id) WHERE (((type)::text = 'RESCHEDULE_REQUIRED'::text) AND (reservation_id IS NOT NULL))` |

- 스키마가 `public`이 아니면 `definition`의 스키마 이름만 다르다.
- 같은 이름의 인덱스가 이미 있던 경우에도 이름만 보고 판단하지 않는다. 위 다섯 항목이 모두 맞아야 적용된 것으로 본다
  (정의가 다르거나 INVALID면 의도한 인덱스가 아니다).

## D. 실패 시 대응

**실패 후 같은 SQL을 그대로 다시 실행하지 않는다.** 이름이 남아 있어 "이미 존재" 오류가 나거나, 원인이 남아 같은 실패가 반복된다.

| 상황 | 증상 | 대응 |
|---|---|---|
| 중복 데이터로 실패 | `could not create unique index ...` (SQLState 23505), `Key (reservation_id, recipient_user_id)=(...) is duplicated` | 1. C 쿼리로 INVALID 인덱스가 남았는지 확인 2. A-1로 중복 목록 확인 3. 중복 정리 방법은 운영자 승인 후 결정 (자동 삭제 금지) |
| 실행 도중 새 중복이 들어와 실패 | 위와 같은 오류인데, 적용 직전 A-1은 0행이었음 | `CONCURRENTLY`는 쓰기를 막지 않으므로 생성 중에 들어온 행도 검사한다. 생성 도중 같은 예약·수신자의 재조정 알림이 새로 저장되면 실패할 수 있다. 1. A-1을 다시 실행해 생성 중에 생긴 중복(`created_at`)인지 확인 2. 그 행을 만든 경로(수동 INSERT, 잠금 없는 새 생성 경로 등)를 찾는다. 현재 애플리케이션은 의뢰 잠금 + 사전 확인으로 중복을 만들지 않으므로 다른 원인을 먼저 의심한다 3. 이후는 "INVALID 인덱스가 남음"과 같다 |
| INVALID 인덱스가 남음 | `indisvalid = false` | INVALID 인덱스는 조회에 쓰이지 않는다. `indisready`에 따라 영향이 다르다 (아래 참고). **운영자 승인 후** `DROP INDEX CONCURRENTLY uk_notifications_reservation_recipient_reschedule;`로 제거하고, 원인을 해결한 뒤 A부터 다시 진행한다 |
| 실행 도중 연결 끊김·취소 | 이름은 있으나 `indisvalid = false` 또는 `indisready = false` | 위와 같다 (승인 후 제거 → A부터 재진행) |
| 트랜잭션 블록 안에서 실행 | `CREATE INDEX CONCURRENTLY cannot run inside a transaction block` | 인덱스는 만들어지지 않는다. autocommit으로 바꿔 B만 다시 실행한다 |

INVALID 인덱스의 `indisready`별 영향:

- `indisvalid = false, indisready = false`: 기존 중복 때문에 **첫 번째 테이블 스캔(빌드)** 에서 실패한 경우.
  쓰기 때 갱신되지 않고 UNIQUE도 검사하지 않는다 (`NotificationRescheduleUniqueIndexTest`에서 확인: 이 상태에서 새 중복 INSERT가 허용됨).
- `indisvalid = false, indisready = true`: **두 번째 스캔(검증)** 단계 이후 실패·취소된 경우.
  PostgreSQL 문서에 따르면 이 단계부터는 쓰기 때 인덱스가 갱신되고 다른 트랜잭션의 INSERT에 UNIQUE가 적용될 수 있다.
  즉 "적용 실패"인데도 애플리케이션에서 23505가 날 수 있다 (재시도 흐름은 아래 "애플리케이션 동작" 참고). 테스트로 재현하지 않았으므로 C 쿼리로 실제 상태를 확인한다.

## 애플리케이션 동작 (인덱스 적용 후)

- 정상 흐름에서는 서비스의 사전 확인이 먼저 막으므로 이 인덱스에 걸리지 않는다.
- 위반이 생기면(잠금 밖 경로 등) `saveAndFlush`가 `DataIntegrityViolationException`을 던지고:
  1. 장애 후속 처리 T2 트랜잭션 전체(예약·의뢰 상태, 이력, 알림)가 롤백된다.
  2. 새 트랜잭션에서 작업이 `FAILED`로 기록된다. `last_error`에는 `RETRYABLE DataIntegrityViolationException sqlState=23505`만 남고 행 값은 남지 않는다.
  3. `next_retry_at` 이후 스케줄러가 다시 처리할 때, 이미 저장된 알림을 사전 확인이 찾아 새로 만들지 않고 성공 처리된다.
- 인덱스를 적용하지 않아도 애플리케이션은 기존처럼 동작한다 (코드 변경 없음).
