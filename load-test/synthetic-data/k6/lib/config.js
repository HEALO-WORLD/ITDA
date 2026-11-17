// 공통 설정. 모든 값은 -e 이름=값 으로 바꿀 수 있다
import { kstToday } from './time.js';

export const BASE_URL = __ENV.BASE_URL || 'http://host.docker.internal:8080';   // 게이트웨이
export const PASSWORD = __ENV.TEST_PASSWORD || 'Loadtest!234';
export const DATA_DIR = __ENV.DATA_DIR || '/work/data';                          // run-k6.sh가 마운트하는 경로
export const REQ_TIMEOUT = __ENV.REQ_TIMEOUT || '30s';

// 기준일. 시드(gen_seed.py)의 기준일과 같아야 슬롯·오늘 검사가 맞는다
export const TODAY = __ENV.TODAY || kstToday();

// 재생 속도 배수. 60이면 1시간을 1분에 재생한다 (대기 시간만 줄인다. 연결 확인용)
export const TIME_SCALE = Number(__ENV.TIME_SCALE || 1);

// 예약 상태 조회 간격과, 확정·실패를 끝까지 기다리는 최대 시간
export const STATUS_POLL_MS = Number(__ENV.STATUS_POLL_MS || 1000);
export const CONFIRM_TIMEOUT_SEC = Number(__ENV.CONFIRM_TIMEOUT_SEC || 30);

// 검사관리자 접수 목록 새로고침
export const POLL_SEC = Number(__ENV.POLL_SEC || 30);
export const POLL_JITTER_SEC = Number(__ENV.POLL_JITTER_SEC || 5);
export const POLL_PAGE_SIZE = Number(__ENV.POLL_PAGE_SIZE || 20);

// access token 유효 1시간. 그 전에 재발급한다 (검사관리자)
export const TOKEN_REFRESH_SEC = Number(__ENV.TOKEN_REFRESH_SEC || 55 * 60);

// API별 응답 시간 기준 (p95, ms)
export const P95_MS = Number(__ENV.P95_MS || 500);
// 생성 → 확정·실패까지 기준 (p95, ms)
// saga 한 바퀴에 Outbox 발행이 4번(HoldRequested → SlotHeld → BookSlot → SlotBooked)이고 발행 주기가 1초라
// 부하가 없어도 2초 안팎이 걸린다. 상태 조회 간격(1초) 오차까지 감안해 5초로 둔다
export const CONFIRM_P95_MS = Number(__ENV.CONFIRM_P95_MS || 5000);
