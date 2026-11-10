# 검사기관 공통 알림 테이블 적용 절차

## 목적

검사기관(EXAMINER) 알림 본문은 기관당 1건만 만들고, 같은 기관 사용자들이 같은 알림을 조회한다.
읽음 여부와 안 읽은 수는 **사용자마다 따로** 관리한다. 기존 개인 알림 `p_notifications`는 변경하지 않는다.

| 테이블 | 역할 | Entity |
|---|---|---|
| `p_institution_notifications` | 알림 본문 (기관당 1건) | `InstitutionNotification` |
| `p_institution_notification_reads` | 사용자별 읽음 기록 (행이 있으면 읽음) | `InstitutionNotificationRead` |

| 구분 | 내용 |
|---|---|
| SQL | `institution_notifications.sql` (두 테이블을 한 트랜잭션으로 생성) |
| 검증 테스트 | `InstitutionNotificationDdlTest` (SQL을 임시 스키마에 적용해 Entity로 만든 테이블과 비교) |
| 기존 테이블 영향 | 없음 (새 테이블만 생성) |

## 생성 순서

1. `p_institution_notifications`
2. `idx_institution_notifications_institution_created`
3. `p_institution_notification_reads`

읽음 기록은 FK 없이 `notification_id`로 알림을 참조한다(알림 본문을 지우는 기능이 없다). 그래서 순서가 바뀌어도 실패하지 않지만, 위 순서를 유지한다.

## 제약·인덱스

| 이름 | 내용 | 목적 |
|---|---|---|
| `p_institution_notifications_pkey` | `PRIMARY KEY (id)` | |
| `uk_institution_notifications_institution_reservation_type` | `UNIQUE (institution_id, reservation_id, type)` | 중복 Kafka 이벤트·재처리 시 같은 기관·예약·유형 알림 1건 |
| `idx_institution_notifications_institution_created` | `(institution_id, created_at)` | 기관별 최신순 목록 |
| `p_institution_notification_reads_pkey` | `PRIMARY KEY (id)` | |
| `uk_institution_notification_reads_notification_user` | `UNIQUE (notification_id, user_id)` | 사용자당 읽음 기록 1건, 처음 읽은 시각 유지. 목록 LEFT JOIN·안 읽은 수 조회에도 사용 |

애플리케이션은 두 테이블 모두 `INSERT ... ON CONFLICT DO NOTHING`으로 저장한다. UNIQUE 제약이 있어야 중복이 0건으로 처리된다.
제약 없이 배포하면 알림이나 읽음 기록이 중복될 수 있으므로 **애플리케이션 배포 전에 적용**한다.

## 절차

### [A] 적용 전 확인 (읽기 전용)

```sql
SELECT table_name
  FROM information_schema.tables
 WHERE table_name IN ('p_institution_notifications', 'p_institution_notification_reads');

SELECT column_name
  FROM information_schema.columns
 WHERE table_name = 'p_institution_notifications'
   AND column_name IN ('is_read', 'read_at');
```

| 결과 | 다음 단계 |
|---|---|
| 테이블 0개 | [B] |
| 두 테이블 모두 있고 `is_read`·`read_at` 없음 | 적용할 것 없음 → [C]로 구조만 확인 |
| `p_institution_notifications`에 `is_read`·`read_at`이 있음 (이전 구조로 생성된 환경) | [E] |

### [B] 적용

`institution_notifications.sql`을 referral DB에서 실행한다. `BEGIN ... COMMIT` 한 트랜잭션이라 중간에 실패하면 아무것도 남지 않는다.

### [C] 적용 후 확인 (읽기 전용)

```sql
SELECT conrelid::regclass AS table_name, conname, pg_get_constraintdef(oid)
  FROM pg_constraint
 WHERE conrelid IN ('p_institution_notifications'::regclass, 'p_institution_notification_reads'::regclass);

SELECT tablename, indexname, indexdef
  FROM pg_indexes
 WHERE tablename IN ('p_institution_notifications', 'p_institution_notification_reads');
```

기대: 위 제약·인덱스 표의 5개 (PK 2, UNIQUE 2, 인덱스 1).

### [D] 되돌리기

새 테이블이라 다른 데이터에 영향이 없다. 알림 기능을 되돌린 애플리케이션으로 먼저 배포한 뒤 필요할 때만 실행한다.

```sql
BEGIN;
DROP TABLE p_institution_notification_reads;
DROP TABLE p_institution_notifications;
COMMIT;
```

### [E] 이전 구조(기관 공통 읽음)로 이미 테이블이 만들어진 환경

이 기능의 이전 버전(기관 단위 `is_read`·`read_at`)을 ddl-auto로 실행한 로컬·테스트 DB에만 해당한다. 해당 버전은 커밋·배포되지 않았다.

**반드시 새 애플리케이션 배포 전에 실행한다.** 새 애플리케이션은 알림을 저장할 때 `is_read`를 넣지 않는다.
`is_read`는 `NOT NULL`이고 기본값이 없어서 컬럼이 남아 있으면 알림 저장이 실패한다. 그러면 알림을 만드는 예약 확정·취소 트랜잭션까지 롤백된다.
ddl-auto: update는 컬럼을 지우지 않으므로 자동으로 해결되지 않는다.

```sql
BEGIN;
-- 1) 이전 기관 단위 읽음 컬럼 제거 (사용자별 읽은 사람을 알 수 없으므로 기존 읽음 상태는 옮기지 않는다)
ALTER TABLE p_institution_notifications DROP COLUMN is_read, DROP COLUMN read_at;
-- 2) 사용자별 읽음 기록 테이블 생성 (institution_notifications.sql의 두 번째 CREATE TABLE과 같다)
CREATE TABLE p_institution_notification_reads (
    id              uuid         NOT NULL,
    notification_id uuid         NOT NULL,
    user_id         uuid         NOT NULL,
    read_at         timestamp(6) NOT NULL,
    CONSTRAINT p_institution_notification_reads_pkey PRIMARY KEY (id),
    CONSTRAINT uk_institution_notification_reads_notification_user
        UNIQUE (notification_id, user_id)
);
COMMIT;
```

결과: 기존 알림은 모든 사용자에게 **안 읽음**으로 보인다. 이전 구조에서 누가 읽었는지는 기록되지 않았으므로 사용자별로 옮길 수 없다.

## 주의

- ddl-auto는 기존 테이블의 컬럼 삭제·제약 변경을 보장하지 않는다. 구조를 바꿀 때는 이 문서처럼 별도 SQL로 적용한다.
- 개발·운영 DB에는 이 저장소의 테스트가 접근하지 않는다. 검증은 Testcontainers PostgreSQL에서만 한다.
