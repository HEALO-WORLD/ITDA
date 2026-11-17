#!/usr/bin/env bash
# 테스트 후: 밀린 비동기 처리(Outbox 발행·saga)가 끝나길 기다린 뒤, DB 전체 행 기준으로 정합성을 검사한다
# 동시성 테스트의 최종 판정이며, 다른 테스트 뒤에도 실행한다 (모든 항목이 0이면 통과)
#
#   ./after-test.sh                  기본 최대 300초 대기
#   DRAIN_TIMEOUT=600 ./after-test.sh
set -euo pipefail
cd "$(dirname "$0")"

CONTAINER="${CONTAINER:-itda-postgres}"
PGUSER="${PGUSER:-itda}"
DRAIN_TIMEOUT="${DRAIN_TIMEOUT:-300}"
SEED_ID='f1______-0000-0000-0000-%'   # run-seed.sh와 같은 시드 ID 패턴

q() {  # q <db> <sql>
  docker exec -i "$CONTAINER" psql -U "$PGUSER" -d "$1" -v ON_ERROR_STOP=1 -q -At -c "$2"
}

echo "== 밀린 처리 대기 (최대 ${DRAIN_TIMEOUT}초)"
start=$(date +%s)
while :; do
  ref_out=$(q referral_db "SELECT count(*) FROM p_outbox_events WHERE published_at IS NULL")
  slot_out=$(q slot_db "SELECT count(*) FROM p_outbox_events WHERE published_at IS NULL")
  in_progress=$(q referral_db "SELECT count(*) FROM p_reservations WHERE reservation_status IN ('REQUESTED','HELD')")
  held=$(q slot_db "SELECT count(*) FROM p_slots WHERE status = 'HELD'")
  elapsed=$(($(date +%s) - start))
  echo "  ${elapsed}s  미발행 Outbox referral=$ref_out slot=$slot_out, 진행 중 예약=$in_progress, HELD 슬롯=$held"
  if [ "$ref_out$slot_out$in_progress$held" = "0000" ]; then break; fi
  if [ "$elapsed" -ge "$DRAIN_TIMEOUT" ]; then echo "  대기 시간 초과: 남은 처리가 있는 상태로 검사합니다"; break; fi
  sleep 5
done

echo "== 정합성 검사 (0이어야 통과)"
fail=0
check() {  # check <설명> <값>
  local mark="OK"
  [ "$2" = "0" ] || { mark="실패"; fail=1; }
  printf '  %-58s %6s  %s\n' "$1" "$2" "$mark"
}

check "한 슬롯에 활성 예약(REQUESTED·HELD·CONFIRMED) 2건 이상" \
  "$(q referral_db "SELECT count(*) FROM (SELECT slot_id FROM p_reservations WHERE reservation_status IN ('REQUESTED','HELD','CONFIRMED') AND deleted_at IS NULL GROUP BY slot_id HAVING count(*) > 1) x")"

check "한 의뢰에 활성 예약 2건 이상" \
  "$(q referral_db "SELECT count(*) FROM (SELECT referral_id FROM p_reservations WHERE reservation_status IN ('REQUESTED','HELD','CONFIRMED') AND deleted_at IS NULL GROUP BY referral_id HAVING count(*) > 1) x")"

# 확정 예약 ↔ BOOKED 슬롯이 정확히 같은 (슬롯, 예약) 쌍인지 (서로 다른 DB라 목록을 내려받아 비교)
tmp=$(mktemp -d)
q referral_db "SELECT slot_id || ',' || id FROM p_reservations WHERE reservation_status = 'CONFIRMED' AND deleted_at IS NULL ORDER BY 1" > "$tmp/ref"
q slot_db "SELECT id || ',' || reservation_id FROM p_slots WHERE status = 'BOOKED' AND deleted_at IS NULL ORDER BY 1" > "$tmp/slot"
check "확정 예약인데 슬롯이 BOOKED가 아니거나 다른 예약에 묶임" "$(comm -23 "$tmp/ref" "$tmp/slot" | wc -l | tr -d ' ')"
check "BOOKED 슬롯인데 확정 예약이 없음 (오버부킹·유령 예약)" "$(comm -13 "$tmp/ref" "$tmp/slot" | wc -l | tr -d ' ')"
rm -rf "$tmp"

check "취소된 의뢰인데 검사가 시작·완료됨 (취소 vs 시작 경합)" \
  "$(q referral_db "SELECT count(*) FROM p_referrals f JOIN p_examinations e ON e.referral_id = f.id WHERE f.referral_status = 'CANCELED' AND e.status IN ('IN_PROGRESS','COMPLETED')")"

check "검사가 시작·완료됐는데 예약이 확정 상태가 아님" \
  "$(q referral_db "SELECT count(*) FROM p_examinations e JOIN p_reservations r ON r.id = e.reservation_id WHERE e.status IN ('IN_PROGRESS','COMPLETED') AND r.reservation_status <> 'CONFIRMED'")"

check "결과가 등록됐는데 검사가 완료 상태가 아님" \
  "$(q referral_db "SELECT count(*) FROM p_examination_results x JOIN p_examinations e ON e.id = x.examination_id WHERE e.status <> 'COMPLETED'")"

check "의뢰의 현재 예약이 그 의뢰의 예약이 아님" \
  "$(q referral_db "SELECT count(*) FROM p_referrals f LEFT JOIN p_reservations r ON r.id = f.current_reservation_id WHERE f.current_reservation_id IS NOT NULL AND (r.id IS NULL OR r.referral_id <> f.id)")"

check "DLT에 처리 대기 메시지 (referral·slot 합계)" \
  "$(( $(q referral_db "SELECT count(*) FROM p_dead_letter_events WHERE dead_letter_status = 'PENDING'") + $(q slot_db "SELECT count(*) FROM p_dead_letter_events WHERE dead_letter_status = 'PENDING'") ))"

echo "== 이번 회차에 테스트가 만든 데이터 (시드 ID 패턴이 아닌 행)"
q referral_db "SELECT '  의뢰 ' || referral_status || ' ' || count(*) || '건' FROM p_referrals WHERE id::text NOT LIKE '$SEED_ID' GROUP BY referral_status ORDER BY 1"
q referral_db "SELECT '  검사 ' || status || ' ' || count(*) || '건 (오늘 검사 예정분 처리 결과 포함)' FROM p_examinations WHERE updated_at::date = CURRENT_DATE GROUP BY status ORDER BY 1"

# 생성 → 확정·실패까지 걸린 시간을 DB 시각으로 잰다 (k6 상태 조회 간격과 무관한 밀리초 단위 값, 추가 요청 없음)
#   확정: confirmed_at - created_at / 실패(슬롯 경합 등): ended_at - created_at
#   1분 단위로 묶어 스트레스 단계·스파이크 구간과 시간으로 맞춰 본다 (시각은 한국 시간)
echo "== 생성 → 확정·실패 시간 (DB 기준, 이번 회차 예약, 1분 단위)"
docker exec -i "$CONTAINER" psql -U "$PGUSER" -d referral_db -q -c "
  WITH r AS (
    SELECT date_trunc('minute', created_at) AS minute,
           reservation_status,
           extract(epoch FROM (coalesce(confirmed_at, ended_at) - created_at)) * 1000 AS ms
      FROM p_reservations
     WHERE id::text NOT LIKE '$SEED_ID'
  )
  SELECT to_char(minute, 'HH24:MI') AS 분,
         count(*) AS 생성,
         count(*) FILTER (WHERE reservation_status IN ('CONFIRMED', 'CANCELED')) AS 확정,
         count(*) FILTER (WHERE reservation_status = 'FAILED') AS 실패,
         round(percentile_cont(0.5) WITHIN GROUP (ORDER BY ms)::numeric) AS p50_ms,
         round(percentile_cont(0.95) WITHIN GROUP (ORDER BY ms)::numeric) AS p95_ms,
         round(max(ms)::numeric) AS max_ms
    FROM r WHERE ms IS NOT NULL
   GROUP BY minute
   UNION ALL
  SELECT '전체', count(*),
         count(*) FILTER (WHERE reservation_status IN ('CONFIRMED', 'CANCELED')),
         count(*) FILTER (WHERE reservation_status = 'FAILED'),
         round(percentile_cont(0.5) WITHIN GROUP (ORDER BY ms)::numeric),
         round(percentile_cont(0.95) WITHIN GROUP (ORDER BY ms)::numeric),
         round(max(ms)::numeric)
    FROM r WHERE ms IS NOT NULL
   ORDER BY 1"

[ "$fail" = "0" ] && echo "정합성 검사 통과" || { echo "정합성 검사 실패"; exit 1; }
