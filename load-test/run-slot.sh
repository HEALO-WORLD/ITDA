#!/usr/bin/env bash
# slot 단독 부하 테스트를 회차 수만큼 반복한다 (Git Bash, 프로젝트 루트에서 실행)
#   회차마다: reset → k6 → 밀린 Outbox 발행 대기 → 결과 SQL 저장 → GC·부하 기록 → 쉬기
#
# 사용법
#   bash load-test/run-slot.sh <단계 이름> <fixed|ramp> [회차 수=3]
#   예) bash load-test/run-slot.sh before fixed
#       bash load-test/run-slot.sh after-ac ramp
#
# 결과
#   load-test/results/slot-<단계>-<시나리오>-<n>.json   k6 요약
#   load-test/results/slot-<단계>-<시나리오>-<n>.txt    result-slot.sql 출력
#   load-test/results/slot-runs.log                     회차별 커밋, 시작·끝 시각, GC 최대, 시스템 부하 최대
#
# 전제: slot-service·prometheus 등이 떠 있고, itda-k6-kafka 이미지가 있다
#       (docs/work/1112-slot-Outbox-개선과-단독-부하-테스트-순서.md 0단계)
set -euo pipefail

STAGE=${1:?단계 이름이 필요하다 (예: before, after-ac)}
SCENARIO=${2:?시나리오가 필요하다 (fixed 또는 ramp)}
ROUNDS=${3:-3}
REST_SECONDS=${REST_SECONDS:-60}          # 회차 사이에 쉬는 시간
DRAIN_TIMEOUT=${DRAIN_TIMEOUT:-900}       # 밀린 Outbox를 기다리는 최대 시간(초)

case "$SCENARIO" in
    fixed) SLOTS=10000; K6_OPTS=(-e RATE=20 -e DURATION=1m) ;;
    ramp)  SLOTS=30000; K6_OPTS=(-e MODE=ramp -e LEVELS=10,30,50,70,90 -e STEP=1m) ;;
    *) echo "시나리오는 fixed 또는 ramp: $SCENARIO" >&2; exit 1 ;;
esac

RESULTS=load-test/results
LOG=$RESULTS/slot-runs.log
mkdir -p "$RESULTS"
HOST_DIR=$(cygpath -w "$PWD/load-test" 2>/dev/null || echo "$PWD/load-test")
COMMIT=$(git rev-parse --short HEAD)

psql_slot() {
    docker compose exec -T postgres sh -c 'psql -U "$POSTGRES_USER" -d slot_db '"$*"
}

# Prometheus 즉시 쿼리 결과의 최댓값 하나 (없으면 -)
prom_max() {
    curl -s -G http://localhost:9090/api/v1/query --data-urlencode "query=$1" \
        | python -c "import sys,json; r=json.load(sys.stdin)['data']['result']; print(max((float(x['value'][1]) for x in r), default='-'))" \
        2>/dev/null || echo "-"
}

for n in $(seq 1 "$ROUNDS"); do
    name="slot-$STAGE-$SCENARIO-$n"
    echo "=== $name 시작 ($(date '+%H:%M:%S'))"

    psql_slot "-q -v slots=$SLOTS" < load-test/sql/reset-slot.sql > /dev/null
    start_epoch=$(date +%s)
    start=$(date '+%Y-%m-%d %H:%M:%S')

    MSYS_NO_PATHCONV=1 docker run --rm -i --network itda_default -v "$HOST_DIR:/load-test" \
        itda-k6-kafka run -q -e BROKER=kafka:29092 -e SLOTS=$SLOTS "${K6_OPTS[@]}" \
        --summary-export "/load-test/results/$name.json" /load-test/k6/slot-commands.js \
        > "$RESULTS/$name.k6.txt" 2>&1 || echo "  k6 종료 코드가 0이 아니다 ($RESULTS/$name.k6.txt 확인)"
    k6_end=$(date '+%H:%M:%S')

    # 밀린 Outbox가 모두 발행될 때까지 기다린다
    waited=0
    while :; do
        left=$(psql_slot "-tA -c \"SELECT count(*) FROM p_outbox_events WHERE published_at IS NULL\"" | tr -d '[:space:]')
        [ "$left" = "0" ] && break
        if [ "$waited" -ge "$DRAIN_TIMEOUT" ]; then
            echo "  ${DRAIN_TIMEOUT}초가 지나도 미발행 $left건이 남아 중단한다" >&2
            exit 1
        fi
        sleep 5; waited=$((waited + 5))
    done
    end=$(date '+%Y-%m-%d %H:%M:%S')
    sleep 20   # Prometheus가 마지막 구간을 한 번 더 수집할 시간 (15초 간격)

    psql_slot "" < load-test/sql/result-slot.sql > "$RESULTS/$name.txt"

    window=$(( $(date +%s) - start_epoch ))s
    gc_max=$(prom_max "max_over_time(jvm_gc_pause_seconds_max{application=\"slot-service\"}[$window])")
    load_max=$(prom_max "max_over_time(system_load_average_1m{application=\"slot-service\"}[$window])")

    line="$name commit=$COMMIT start=\"$start\" k6_end=$k6_end end=\"$end\" drain=${waited}s gc_pause_max=${gc_max}s load_max=$load_max"
    echo "$line" | tee -a "$LOG"

    if [ "$n" -lt "$ROUNDS" ]; then
        sleep "$REST_SECONDS"
    fi
done
echo "=== 완료 ($(date '+%H:%M:%S'))"
