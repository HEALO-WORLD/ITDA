-- 예약 정합성 복구 실패 기록 테이블 (p_reconciliation_failures)
--
-- 이 파일은 애플리케이션이 자동 실행하지 않는다. ddl-auto: update 환경에서는 애플리케이션 기동 시 같은 구조로 만들어지지만,
-- ddl-auto를 끈 환경이나 배포 전에 미리 만들 때 운영자가 직접 실행한다
-- Entity(ReconciliationFailure)와 컬럼·제약 이름을 맞춘다
--
-- - 예약당 1건 (uk_reconciliation_failures_reservation). 같은 예약의 재실패는 횟수·마지막 실패 정보만 갱신한다
-- - status = 'MANUAL_REVIEW'인 예약은 정합성 점검 대상에서 제외된다. 담당자가 확인을 마치고 다시 자동 점검하려면 행을 지운다
-- - 새 테이블만 만들므로 기존 데이터에 영향이 없다
BEGIN;

CREATE TABLE p_reconciliation_failures (
    id              uuid         NOT NULL,
    reservation_id  uuid         NOT NULL,
    result_type     varchar(50)  NOT NULL,
    status          varchar(20)  NOT NULL,
    failure_count   integer      NOT NULL,
    error_code      varchar(100) NOT NULL,
    failure_reason  varchar(500) NOT NULL,
    first_failed_at timestamp(6) NOT NULL,
    last_failed_at  timestamp(6) NOT NULL,
    CONSTRAINT p_reconciliation_failures_pkey PRIMARY KEY (id),
    CONSTRAINT uk_reconciliation_failures_reservation UNIQUE (reservation_id),
    CONSTRAINT p_reconciliation_failures_status_check CHECK (status IN ('RETRYING', 'MANUAL_REVIEW'))
);

COMMIT;
