#!/usr/bin/env bash
# main 배포 스크립트. Deploy 워크플로가 SSM으로 git pull 직후 ec2-user로 실행한다
# 사용: deploy.sh <pull 전 커밋> [서비스... | all]
#   서비스를 주지 않으면 pull 전 커밋과 비교해 바뀐 서비스만 다시 빌드·기동한다
set -euo pipefail
cd "$(dirname "$0")/.."

OLD="$1"; shift
APPS=(eureka-server gateway-service institution-service slot-service referral-service)
DC=(docker compose -f docker-compose.yml -f docker-compose.app.yml)

# 배포 대상 결정
if [ $# -gt 0 ]; then
  TARGETS=("$@")
else
  CHANGED=$(git diff --name-only "$OLD" HEAD)
  TARGETS=()
  if grep -qE '^(docker-compose[^/]*\.yml|docker/)' <<<"$CHANGED"; then
    TARGETS=(all)   # compose·인프라 설정이 바뀌면 전체를 다시 띄운다
  else
    for s in "${APPS[@]}"; do
      if grep -q "^$s/" <<<"$CHANGED"; then TARGETS+=("$s"); fi
    done
  fi
fi

if [ ${#TARGETS[@]} -eq 0 ]; then
  echo "바뀐 서비스가 없어 배포를 건너뜁니다 ($(git rev-parse --short HEAD))"
  exit 0
fi

if [ "${TARGETS[0]}" = all ]; then
  TARGETS=("${APPS[@]}")
  UP_ARGS=()   # 서비스를 지정하지 않으면 인프라까지 전체를 맞춘다
else
  for s in "${TARGETS[@]}"; do
    if [[ ! " ${APPS[*]} " =~ " $s " ]]; then
      echo "알 수 없는 서비스입니다: $s (가능: ${APPS[*]}, all)"
      exit 1
    fi
  done
  UP_ARGS=("${TARGETS[@]}")
fi
echo "배포 대상: ${TARGETS[*]} ($(git rev-parse --short "$OLD") → $(git rev-parse --short HEAD))"

# 빌드 로그는 파일로 남긴다 (SSM 출력은 앞부분 24KB만 남아 뒤쪽 결과가 잘리기 때문)
BUILD_LOG=/tmp/itda-build.log
if ! "${DC[@]}" build "${UP_ARGS[@]}" > "$BUILD_LOG" 2>&1; then
  echo "이미지 빌드에 실패했습니다. 전체 로그: $BUILD_LOG"
  tail -50 "$BUILD_LOG"
  exit 1
fi
"${DC[@]}" up -d --no-build "${UP_ARGS[@]}"

# 헬스체크 통과 대기 (서비스당 최대 5분)
wait_healthy() {
  local c="itda-$1" s=missing
  for _ in $(seq 1 60); do
    s=$(docker inspect -f '{{.State.Health.Status}}' "$c" 2>/dev/null || echo missing)
    if [ "$s" = healthy ]; then echo "$1: healthy"; return 0; fi
    if [ "$s" = unhealthy ]; then break; fi
    sleep 5
  done
  echo "$1: 헬스체크에 실패했습니다 ($s)"
  docker logs --tail 50 "$c" 2>&1 || true
  return 1
}

FAILED=0
for s in "${TARGETS[@]}"; do
  wait_healthy "$s" || FAILED=1
done

# 빌드할 때마다 쌓이는 이름 없는 이미지 정리 (EBS 30GB)
docker image prune -f > /dev/null

if [ $FAILED -ne 0 ]; then
  echo "배포 실패"
  exit 1
fi
echo "배포 완료 ($(git rev-parse --short HEAD))"
