#!/usr/bin/env bash
# k6 테스트 실행 (Docker로 k6를 띄운다. 로컬에 k6 설치 불필요)
#
#   ./run-k6.sh baseline [k6 옵션...]      01_baseline.js
#   ./run-k6.sh stress                     02_stress.js
#   ./run-k6.sh spike                      03_spike.js
#   ./run-k6.sh soak                       04_soak.js
#   ./run-k6.sh concurrency                05_concurrency.js
#   예) ./run-k6.sh baseline -e TIME_SCALE=60          (연결 확인용 빠른 재생)
#       ./run-k6.sh baseline -e WINDOW_START=11:45 -e WINDOW_END=13:15
#
# 결과: results/<테스트>-<시각>.json (k6 요약), results/<테스트>-<시각>.log (콘솔 출력)
#
# 권장 순서 (회차마다):
#   ./reset-db.sh restore  →  ./run-k6.sh <테스트>  →  ./after-test.sh
# 전제: 게이트웨이(8080)와 서비스가 떠 있고, 시드 기준일이 오늘이다 (./reset-db.sh status로 확인)
set -euo pipefail
cd "$(dirname "$0")"

NAME="${1:?테스트 이름이 필요합니다: baseline|stress|spike|soak|concurrency}"
shift
case "$NAME" in
  baseline) FILE=01_baseline.js ;;
  stress) FILE=02_stress.js ;;
  spike) FILE=03_spike.js ;;
  soak) FILE=04_soak.js ;;
  concurrency) FILE=05_concurrency.js ;;
  *) echo "알 수 없는 테스트: $NAME" >&2; exit 1 ;;
esac

IMAGE="${K6_IMAGE:-itda-k6-kafka}"            # load-test/k6/Dockerfile.kafka (k6 v1.8.2). 없으면 K6_IMAGE=grafana/k6:1.8.2
BASE_URL="${BASE_URL:-http://host.docker.internal:8080}"
HOST_DIR=$(cygpath -w "$PWD" 2>/dev/null || echo "$PWD")
STAMP=$(date +%Y%m%d-%H%M%S)
mkdir -p results
OUT="results/$NAME-$STAMP"

echo "테스트: $FILE, 대상: $BASE_URL, 결과: $OUT.json"
MSYS_NO_PATHCONV=1 docker run --rm -i \
  --add-host host.docker.internal:host-gateway \
  -v "$HOST_DIR:/work" \
  "$IMAGE" run \
  -e BASE_URL="$BASE_URL" -e DATA_DIR=/work/data \
  --summary-export "/work/$OUT.json" \
  "$@" "/work/k6/tests/$FILE" 2>&1 | tee "$OUT.log"
