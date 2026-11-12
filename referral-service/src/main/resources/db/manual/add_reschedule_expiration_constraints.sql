-- IT:DA | Referral reschedule expiration - PostgreSQL 18
-- Purpose: allow EXPIRED referral status and RESCHEDULE_TIMEOUT history reason.
-- Scope: p_referrals, p_referral_histories; no business records are modified.
-- IMPORTANT: Before running on EC2, compare current CHECK definitions against
-- the expected baseline below; do not overwrite constraints that allow additional values.
-- Safe to re-run when the expected baseline remains unchanged (drops/recreates constraints).
-- Prerequisites: application must understand ReferralStatus.EXPIRED and Reason.RESCHEDULE_TIMEOUT.

BEGIN;
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '30s';

-- Both existing status constraints must preserve:
-- PENDING, CONFIRMED, FAILED, CANCELED, NEEDS_RESCHEDULE.
ALTER TABLE p_referrals
    DROP CONSTRAINT p_referrals_referral_status_check;
ALTER TABLE p_referrals
    ADD CONSTRAINT p_referrals_referral_status_check
    CHECK (referral_status IN (
        'PENDING', 'CONFIRMED', 'FAILED', 'CANCELED', 'NEEDS_RESCHEDULE', 'EXPIRED'
    ));

-- Support EXPIRED in both source and destination statuses.
ALTER TABLE p_referral_histories
    DROP CONSTRAINT p_referral_histories_from_status_check;
ALTER TABLE p_referral_histories
    ADD CONSTRAINT p_referral_histories_from_status_check
    CHECK (from_status IN (
        'PENDING', 'CONFIRMED', 'FAILED', 'CANCELED', 'NEEDS_RESCHEDULE', 'EXPIRED'
    ));

ALTER TABLE p_referral_histories
    DROP CONSTRAINT p_referral_histories_to_status_check;
ALTER TABLE p_referral_histories
    ADD CONSTRAINT p_referral_histories_to_status_check
    CHECK (to_status IN (
        'PENDING', 'CONFIRMED', 'FAILED', 'CANCELED', 'NEEDS_RESCHEDULE', 'EXPIRED'
    ));

-- Preserve all existing reasons and add the automated expiry reason.
ALTER TABLE p_referral_histories
    DROP CONSTRAINT p_referral_histories_reason_check;
ALTER TABLE p_referral_histories
    ADD CONSTRAINT p_referral_histories_reason_check
    CHECK (reason IN (
        'REFERRAL_CREATED', 'RESERVATION_CONFIRMED', 'RESERVATION_FAILED',
        'RESERVATION_BLOCKED', 'RESCHEDULED', 'CANCELED_BY_REQUESTER',
        'RESCHEDULE_TIMEOUT'
    ));

COMMIT;

-- Verification: each constraint must include EXPIRED or RESCHEDULE_TIMEOUT.
SELECT
    conrelid::regclass AS table_name,
    conname,
    pg_get_constraintdef(oid) AS definition,
    convalidated AS validated
FROM pg_constraint
WHERE conrelid IN ('p_referrals'::regclass, 'p_referral_histories'::regclass)
  AND conname IN (
      'p_referrals_referral_status_check',
      'p_referral_histories_from_status_check',
      'p_referral_histories_to_status_check',
      'p_referral_histories_reason_check'
  )
ORDER BY table_name, conname;
