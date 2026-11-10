-- 검사기관 공통 알림 테이블 (최종 구조)
--  1) p_institution_notifications      : 알림 본문 (기관당 1건)
--  2) p_institution_notification_reads : 사용자별 읽음 기록 (사용자당 1건)
-- 절차 문서: institution_notifications.md
--
-- 이 파일은 애플리케이션이 자동 실행하지 않는다. ddl-auto: update 환경에서는 애플리케이션 기동 시 같은 구조로 만들어지지만,
-- ddl-auto를 끈 환경이나 배포 전에 미리 만들 때 운영자가 직접 실행한다
-- Entity(InstitutionNotification, InstitutionNotificationRead)와 컬럼·제약 이름·인덱스 이름을 맞춘다
-- (InstitutionNotificationDdlTest가 이 파일을 임시 스키마에 적용해 Entity로 만든 테이블과 비교한다)
--
-- 주의
-- - 테이블이 하나라도 이미 있으면 실패한다. 절차 문서 [A]의 확인 쿼리로 먼저 확인한다
--   (이전 구조 p_institution_notifications에 is_read·read_at이 있는 환경은 절차 문서 [E]를 따른다)
-- - 하나의 트랜잭션으로 실행한다. 중간에 실패하면 두 테이블·제약·인덱스가 모두 만들어지지 않는다
-- - 새 테이블만 만들므로 기존 데이터·기존 알림 테이블(p_notifications)에 영향이 없다
BEGIN;

CREATE TABLE p_institution_notifications (
    id              uuid         NOT NULL,
    institution_id  uuid         NOT NULL,
    referral_id     uuid         NOT NULL,
    reservation_id  uuid         NOT NULL,
    source_event_id uuid,
    type            varchar(50)  NOT NULL,
    title           varchar(100) NOT NULL,
    message         text         NOT NULL,
    created_at      timestamp(6) NOT NULL,
    CONSTRAINT p_institution_notifications_pkey PRIMARY KEY (id),
    -- 같은 기관 + 같은 예약 + 같은 유형의 알림은 1건 (중복 Kafka 이벤트·재처리 대비)
    CONSTRAINT uk_institution_notifications_institution_reservation_type
        UNIQUE (institution_id, reservation_id, type)
);

-- 기관별 최신 알림 목록 조회용
CREATE INDEX idx_institution_notifications_institution_created
    ON p_institution_notifications (institution_id, created_at);

CREATE TABLE p_institution_notification_reads (
    id              uuid         NOT NULL,
    notification_id uuid         NOT NULL,
    user_id         uuid         NOT NULL,
    read_at         timestamp(6) NOT NULL,
    CONSTRAINT p_institution_notification_reads_pkey PRIMARY KEY (id),
    -- 같은 알림 + 같은 사용자의 읽음 기록은 1건 (재요청·동시 요청에도 처음 read_at 유지)
    -- 목록·안 읽은 수 조회의 (notification_id, user_id) 조건도 이 제약의 인덱스를 쓴다
    CONSTRAINT uk_institution_notification_reads_notification_user
        UNIQUE (notification_id, user_id)
);

COMMIT;
