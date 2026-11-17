#!/usr/bin/env bash
# 부하 테스트 시드 데이터 생성·적재·검증 (로컬 Docker PostgreSQL 전용)
#
#   ./run-seed.sh                 생성 → 적재 → 검증 (기준일: 오늘, 한국 시간)
#   ./run-seed.sh --today 2026-11-18   기준일 지정 (gen_seed.py 옵션을 그대로 넘김)
#   ./run-seed.sh load            seed-out/에 이미 만든 CSV만 적재 → 검증
#   ./run-seed.sh verify          검증만
#   ./run-seed.sh cleanup         시드 데이터(시드 ID 패턴 f1______-0000-0000-0000-…)만 삭제
#
# 슬롯·검사 일정이 기준일에 맞춰지므로, 날짜가 바뀌면 cleanup 후 다시 실행한다.
# 적재 중에는 서비스(앱)를 꺼 두는 것을 권장한다 (스케줄러가 적재 도중의 데이터를 읽지 않도록).
set -euo pipefail
cd "$(dirname "$0")"

CONTAINER="${CONTAINER:-itda-postgres}"
PGUSER="${PGUSER:-itda}"
OUT="seed-out"
# 시드 ID 패턴: f1 + 종류 + 0000-0000-0000-0000-순번. 앱이 만드는 UUID(v4·v7)는 세 번째 묶음이 4·7로 시작해 겹치지 않는다
SEED_ID='f1______-0000-0000-0000-%'

psql_db() {  # psql_db <db> [psql 인자...]
  local db="$1"; shift
  docker exec -i "$CONTAINER" psql -U "$PGUSER" -d "$db" -v ON_ERROR_STOP=1 -q "$@"
}

# FK 순서대로 적재한다
INSTITUTION_TABLES="p_institutions p_users p_partnerships p_partnership_histories p_exam_types p_equipments p_equipment_exam_types p_equipment_operating_hours"
SLOT_TABLES="p_slots p_slot_status_histories p_outbox_events p_consumed_events"
REFERRAL_TABLES="p_referrals p_reservations p_referral_histories p_examinations p_examination_results p_institution_notifications p_outbox_events p_consumed_events"

tables_of() {
  case "$1" in
    institution_db) echo "$INSTITUTION_TABLES" ;;
    slot_db) echo "$SLOT_TABLES" ;;
    referral_db) echo "$REFERRAL_TABLES" ;;
  esac
}

check_target() {
  # 로컬 컨테이너인지 확인한다 (운영 DB에 붙지 않도록)
  local host
  host=$(docker exec "$CONTAINER" hostname)
  echo "대상: 컨테이너 $CONTAINER (호스트 $host), 사용자 $PGUSER"
}

seeded_count() {
  psql_db institution_db -At -c "SELECT count(*) FROM p_institutions WHERE id::text LIKE '$SEED_ID'"
}

generate() {
  python gen_seed.py "$@"
}

load() {
  [ -d "$OUT" ] || { echo "$OUT/ 가 없습니다. 먼저 생성하세요."; exit 1; }
  if [ "$(seeded_count)" != "0" ]; then
    echo "이미 시드 데이터가 있습니다. ./run-seed.sh cleanup 후 다시 실행하세요."
    exit 1
  fi
  for db in institution_db slot_db referral_db; do
    for t in $(tables_of "$db"); do
      local file="$OUT/$db/$t.csv"
      local cols
      cols=$(head -1 "$file")
      printf '  %-14s %-32s ' "$db" "$t"
      psql_db "$db" -c "COPY $t ($cols) FROM STDIN WITH (FORMAT csv, HEADER true)" < "$file"
      echo "$(($(wc -l < "$file") - 1))건"
    done
    psql_db "$db" -c "ANALYZE"
  done
}

verify() {
  echo "== 테이블별 시드 건수 (summary.json과 비교)"
  local fail=0
  for db in institution_db slot_db referral_db; do
    for t in $(tables_of "$db"); do
      local actual expected
      actual=$(psql_db "$db" -At -c "SELECT count(*) FROM $t WHERE id::text LIKE '$SEED_ID'")
      expected=$(python -c "import json;print(json.load(open('$OUT/summary.json',encoding='utf-8'))['tables']['$db.$t'])")
      local mark="OK"
      [ "$actual" = "$expected" ] || { mark="불일치(기대 $expected)"; fail=1; }
      printf '  %-14s %-32s %8s  %s\n' "$db" "$t" "$actual" "$mark"
    done
  done

  echo "== 서비스 간 정합성"
  # 확정 예약과 BOOKED 슬롯이 1:1로 맞는지 (slot_id, reservation_id 쌍 비교)
  local a b
  a=$(psql_db referral_db -At -c "SELECT slot_id || ',' || id FROM p_reservations WHERE reservation_status = 'CONFIRMED' AND id::text LIKE '$SEED_ID' ORDER BY 1" | md5sum | cut -c1-32)
  b=$(psql_db slot_db -At -c "SELECT id || ',' || reservation_id FROM p_slots WHERE status = 'BOOKED' AND id::text LIKE '$SEED_ID' ORDER BY 1" | md5sum | cut -c1-32)
  if [ "$a" = "$b" ]; then echo "  확정 예약 ↔ BOOKED 슬롯 1:1 일치: OK"; else echo "  확정 예약 ↔ BOOKED 슬롯 불일치"; fail=1; fi

  # 예약의 장비가 실제 장비인지
  local eq_ids bad
  eq_ids=$(psql_db institution_db -At -c "SELECT string_agg(quote_literal(id::text), ',') FROM p_equipments WHERE id::text LIKE '$SEED_ID'")
  bad=$(psql_db referral_db -At -c "SELECT count(*) FROM p_reservations WHERE id::text LIKE '$SEED_ID' AND equipment_id::text NOT IN ($eq_ids)")
  if [ "$bad" = "0" ]; then echo "  예약 장비가 모두 실제 장비: OK"; else echo "  없는 장비를 가리키는 예약 $bad건"; fail=1; fi

  # 같은 슬롯에 활성 예약이 둘 이상 없는지
  bad=$(psql_db referral_db -At -c "SELECT count(*) FROM (SELECT slot_id FROM p_reservations WHERE reservation_status IN ('REQUESTED','HELD','CONFIRMED') GROUP BY slot_id HAVING count(*) > 1) x")
  if [ "$bad" = "0" ]; then echo "  슬롯당 활성 예약 1건 이하: OK"; else echo "  활성 예약이 겹친 슬롯 $bad개"; fail=1; fi

  echo "== 기준일 현황"
  psql_db referral_db -c "
    SELECT CASE WHEN r.start_time::date < CURRENT_DATE THEN '지난 검사'
                WHEN r.start_time::date = CURRENT_DATE THEN '오늘 검사'
                ELSE '앞으로 검사' END AS 구분,
           f.referral_status AS 의뢰, r.reservation_status AS 예약, e.status AS 검사, count(*) AS 건수
      FROM p_reservations r
      JOIN p_referrals f ON f.id = r.referral_id
      LEFT JOIN p_examinations e ON e.reservation_id = r.id
     WHERE r.id::text LIKE '$SEED_ID'
     GROUP BY 1, 2, 3, 4 ORDER BY 1, 5 DESC"
  psql_db slot_db -c "
    SELECT CASE WHEN date < CURRENT_DATE THEN '지난 슬롯' WHEN date = CURRENT_DATE THEN '오늘 슬롯' ELSE '앞으로 슬롯' END AS 구분,
           status, count(*) AS 건수
      FROM p_slots WHERE id::text LIKE '$SEED_ID' GROUP BY 1, 2 ORDER BY 1, 2"

  [ "$fail" = "0" ] && echo "검증 통과" || { echo "검증 실패"; exit 1; }
}

cleanup() {
  echo "삭제 대상 (시드 ID 패턴인 행):"
  for db in referral_db slot_db institution_db; do
    for t in $(tables_of "$db"); do
      printf '  %-14s %-32s %s\n' "$db" "$t" "$(psql_db "$db" -At -c "SELECT count(*) FROM $t WHERE id::text LIKE '$SEED_ID'")"
    done
  done
  read -r -p "삭제할까요? [y/N] " yn
  [ "$yn" = "y" ] || { echo "취소"; exit 0; }
  # FK 역순으로 지운다
  for db in referral_db slot_db institution_db; do
    local sql="BEGIN;"
    for t in $(tables_of "$db" | tr ' ' '\n' | tac); do
      sql="$sql DELETE FROM $t WHERE id::text LIKE '$SEED_ID';"
    done
    psql_db "$db" -c "$sql COMMIT;"
    echo "  $db 삭제 완료"
  done
}

check_target
case "${1:-}" in
  load) load; verify ;;
  verify) verify ;;
  cleanup) cleanup ;;
  *) generate "$@"; load; verify ;;
esac
