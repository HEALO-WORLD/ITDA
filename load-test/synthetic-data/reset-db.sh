#!/usr/bin/env bash
# 테스트 사이 DB 리셋: 시드 직후 상태를 템플릿 DB로 떠 두고, 회차마다 그 템플릿으로 DB를 다시 만든다
#
#   ./reset-db.sh snapshot   시드 직후 한 번: institution_db·slot_db·referral_db → *_seed 템플릿으로 복사
#   ./reset-db.sh restore    회차마다: 세 DB를 지우고 *_seed 템플릿에서 다시 만든다 (로컬 약 30초)
#   ./reset-db.sh status     템플릿이 언제·어느 기준일로 만들어졌는지 확인
#
# 파일 단위 복사라 DELETE로 되돌릴 때와 달리 dead tuple·인덱스 부풀림 없이 매번 같은 물리 상태에서 시작한다
# 주의
#   - restore는 세 DB에 붙은 연결을 끊는다. 앱의 커넥션 풀은 다시 연결하지만, 진행 중인 요청은 실패한다
#     → 테스트가 끝나고 ./after-test.sh로 Kafka·Outbox 처리가 끝난 것을 확인한 뒤 실행한다
#   - Redis·Kafka는 되돌리지 않는다
#   - 시드 기준일이 바뀌면(날짜가 넘어가면) ./run-seed.sh cleanup → ./run-seed.sh → ./reset-db.sh snapshot을 다시 한다
set -euo pipefail
cd "$(dirname "$0")"

CONTAINER="${CONTAINER:-itda-postgres}"
PGUSER="${PGUSER:-itda}"
DBS="institution_db slot_db referral_db"

admin() {
  docker exec -i "$CONTAINER" psql -U "$PGUSER" -d postgres -v ON_ERROR_STOP=1 -q -At "$@"
}

kst_today() {
  python -c "import datetime as d;print((d.datetime.now(d.timezone.utc)+d.timedelta(hours=9)).date())"
}

seed_today() {
  python -c "import json;print(json.load(open('seed-out/summary.json',encoding='utf-8'))['today'])" 2>/dev/null || echo "알수없음"
}

snapshot() {
  local today
  today=$(seed_today)
  for db in $DBS; do
    # 복사하는 동안 원본에 아무도 붙지 못하게 막고 기존 연결을 끊는다
    admin -c "ALTER DATABASE $db ALLOW_CONNECTIONS false" \
          -c "SELECT count(pg_terminate_backend(pid)) FROM pg_stat_activity WHERE datname = '$db' AND pid <> pg_backend_pid()" >/dev/null
    admin -c "DROP DATABASE IF EXISTS ${db}_seed" \
          -c "CREATE DATABASE ${db}_seed TEMPLATE $db" \
          -c "COMMENT ON DATABASE ${db}_seed IS 'seed_today=$today created_at=$(date '+%F %T')'" \
          -c "ALTER DATABASE ${db}_seed ALLOW_CONNECTIONS false" || {
      admin -c "ALTER DATABASE $db ALLOW_CONNECTIONS true"; exit 1; }
    admin -c "ALTER DATABASE $db ALLOW_CONNECTIONS true"
    echo "  $db → ${db}_seed (기준일 $today)"
  done
}

restore() {
  local today want
  today=$(kst_today)
  want=$(admin -c "SELECT shobj_description(oid, 'pg_database') FROM pg_database WHERE datname = 'referral_db_seed'")
  [ -n "$want" ] || { echo "템플릿이 없습니다. 먼저 ./reset-db.sh snapshot"; exit 1; }
  if [[ "$want" != *"seed_today=$today"* ]]; then
    echo "경고: 템플릿 기준일과 오늘($today)이 다릅니다 → $want"
    echo "      슬롯·오늘 검사가 맞지 않으니 다시 시드한 뒤 snapshot 하세요. 그래도 복원하려면 FORCE=1"
    [ "${FORCE:-0}" = "1" ] || exit 1
  fi
  for db in $DBS; do
    admin -c "DROP DATABASE $db WITH (FORCE)" -c "CREATE DATABASE $db TEMPLATE ${db}_seed"
    echo "  ${db}_seed → $db"
  done
  # 복원 시각을 남긴다. after-test.sh가 이 시각 이후 생성된 행만 '이번 회차'로 센다 (기존 운영 데이터가 섞이지 않도록)
  # 앱이 created_at을 한국 시간으로 쓰므로 같은 기준으로 적는다
  local at
  at=$(admin -c "SELECT to_char(now() AT TIME ZONE 'Asia/Seoul', 'YYYY-MM-DD HH24:MI:SS.US')")
  admin -c "COMMENT ON DATABASE referral_db IS 'restored_at=$at'"
  echo "  복원 시각 $at"
}

status() {
  admin -c "SELECT datname || '  ' || coalesce(shobj_description(oid, 'pg_database'), '') || '  ' || pg_size_pretty(pg_database_size(datname))
              FROM pg_database WHERE datname LIKE '%\_seed' ORDER BY 1"
  echo "오늘(한국 시간): $(kst_today)"
}

case "${1:-}" in
  snapshot) snapshot ;;
  restore) restore ;;
  status) status ;;
  *) echo "사용법: ./reset-db.sh snapshot|restore|status"; exit 1 ;;
esac
