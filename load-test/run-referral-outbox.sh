#!/usr/bin/env bash
# referral 단독 Outbox 부하 테스트를 회차 수만큼 반복한다 (Git Bash, 프로젝트 루트에서 실행)
#   회차마다: reset → k6 → 밀린 Outbox 발행 대기 → 결과 SQL 저장 → GC·부하 기록 → 쉬기
#   run-slot.sh(#257 slot 단독 테스트)와 같은 순서·규모다
#
# 사용법
#   bash load-test/run-referral-outbox.sh <단계 이름> <fixed|ramp> [회차 수=3]
#   예) bash load-test/run-referral-outbox.sh before fixed
#       bash load-test/run-referral-outbox.sh after ramp
#
# 결과
#   load-test/results/referral-<단계>-<시나리오>-<n>.json   k6 요약
#   load-test/results/referral-<단계>-<시나리오>-<n>.txt    result-referral-outbox.sql 출력
#   load-test/results/referral-runs.log                     회차별 커밋, 시작·끝 시각, GC 최대, 시스템 부하 최대
#
# 전제
#   - referral-service·postgres·kafka·prometheus가 떠 있고, slot-service는 꺼져 있다 (k6/referral-results.js 상단 참고)
#   - itda-k6-kafka 이미지가 있다 (load-test/k6/Dockerfile.kafka)
#   - 끝난 뒤 slot-service를 켜기 전에 slot-commands의 slot-service 소비 위치를 최신으로 옮긴다 (k6/referral-results.js 상단 참고)
set -euo pipefail

STAGE=${1:?단계 이름이 필요하다 (예: before, after)}
SCENARIO=${2:?시나리오가 필요하다 (fixed 또는 ramp)}
ROUNDS=${3:-3}
REST_SECONDS=${REST_SECONDS:-60}          # 회차 사이에 쉬는 시간
DRAIN_TIMEOUT=${DRAIN_TIMEOUT:-900}       # 밀린 Outbox를 기다리는 최대 시간(초)

# #257 slot 단독 테스트와 Outbox 이벤트량을 맞춘다 (slot: 예약 1건 = 이벤트 2건, referral: SlotHeld 1건 = 이벤트 1건)
case "$SCENARIO" in
    fixed) RESERVATIONS=10000; K6_OPTS=(-e RATE=40 -e DURATION=1m) ;;
    ramp)  RESERVATIONS=40000; K6_OPTS=(-e MODE=ramp -e LEVELS=20,60,100,140,180 -e STEP=1m) ;;
    *) echo "시나리오는 fixed 또는 ramp: $SCENARIO" >&2; exit 1 ;;
esac

RESULTS=load-test/results
LOG=$RESULTS/referral-runs.log
mkdir -p "$RESULTS"
HOST_DIR=$(cygpath -w "$PWD/load-test" 2>/dev/null || echo "$PWD/load-test")
COMMIT=$(git rev-parse --short HEAD)

psql_referral() {
    docker compose exec -T postgres sh -c 'psql -U "$POSTGRES_USER" -d referral_db '"$*"
}

# Prometheus 즉시 쿼리 결과의 최댓값 하나 (없으면 -)
prom_max() {
    curl -s -G http://localhost:9090/api/v1/query --data-urlencode "query=$1" \
        | python -c "import sys,json; r=json.load(sys.stdin)['data']['result']; print(max((float(x['value'][1]) for x in r), default='-'))" \
        2>/dev/null || echo "-"
}

for n in $(seq 1 "$ROUNDS"); do
    name="referral-$STAGE-$SCENARIO-$n"
    echo "=== $name 시작 ($(date '+%H:%M:%S'))"

    psql_referral "-q -v reservations=$RESERVATIONS" < load-test/sql/reset-referral-outbox.sql > /dev/null
    start_epoch=$(date +%s)
    start=$(date '+%Y-%m-%d %H:%M:%S')

    MSYS_NO_PATHCONV=1 docker run --rm -i --network itda_default -v "$HOST_DIR:/load-test" \
        itda-k6-kafka run -q -e BROKER=kafka:29092 -e RESERVATIONS=$RESERVATIONS "${K6_OPTS[@]}" \
        --summary-export "/load-test/results/$name.json" /load-test/k6/referral-results.js \
        > "$RESULTS/$name.k6.txt" 2>&1 || echo "  k6 종료 코드가 0이 아니다 ($RESULTS/$name.k6.txt 확인)"
    k6_end=$(date '+%H:%M:%S')

    # 밀린 Outbox가 모두 발행될 때까지 기다린다
    waited=0
    while :; do
        left=$(psql_referral "-tA -c \"SELECT count(*) FROM p_outbox_events WHERE published_at IS NULL\"" | tr -d '[:space:]')
        [ "$left" = "0" ] && break
        if [ "$waited" -ge "$DRAIN_TIMEOUT" ]; then
            echo "  ${DRAIN_TIMEOUT}초가 지나도 미발행 $left건이 남아 중단한다" >&2
            exit 1
        fi
        sleep 5; waited=$((waited + 5))
    done
    end=$(date '+%Y-%m-%d %H:%M:%S')
    sleep 20   # Prometheus가 마지막 구간을 한 번 더 수집할 시간 (15초 간격)

    psql_referral "" < load-test/sql/result-referral-outbox.sql > "$RESULTS/$name.txt"

    window=$(( $(date +%s) - start_epoch ))s
    gc_max=$(prom_max "max_over_time(jvm_gc_pause_seconds_max{application=\"referral-service\"}[$window])")
    load_max=$(prom_max "max_over_time(system_load_average_1m{application=\"referral-service\"}[$window])")

    line="$name commit=$COMMIT start=\"$start\" k6_end=$k6_end end=\"$end\" drain=${waited}s gc_pause_max=${gc_max}s load_max=$load_max"
    echo "$line" | tee -a "$LOG"

    if [ "$n" -lt "$ROUNDS" ]; then
        sleep "$REST_SECONDS"
    fi
done
echo "=== 완료 ($(date '+%H:%M:%S'))"
