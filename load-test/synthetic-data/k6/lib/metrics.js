import { Counter, Trend } from 'k6/metrics';

// 생성 요청 응답 → 의뢰가 CONFIRMED·FAILED가 된 것을 처음 확인할 때까지 (상태 조회 간격만큼 오차가 있다)
export const confirmMs = new Trend('reservation_confirm_ms', true);
export const confirmed = new Counter('reservation_confirmed');
export const holdFailed = new Counter('reservation_failed');
export const unresolved = new Counter('reservation_unresolved');     // 제한 시간 안에 확정·실패가 안 됨

export const businessConflict = new Counter('business_conflict');    // 업무상 409 (에러로 세지 않음)
export const flowError = new Counter('flow_error');                  // 시나리오 진행 불가 (데이터·매핑 문제)
export const noSlotAvailable = new Counter('no_slot_available');     // 빈 슬롯이 없어 예약 포기
export const canceled = new Counter('reservation_canceled');
export const rebooked = new Counter('reservation_rebooked');

// 동시성 판정
export const sameSlotConfirmed = new Counter('same_slot_confirmed');
export const idempotencyViolation = new Counter('idempotency_violation');
export const stateRaceViolation = new Counter('state_race_violation');
export const raceWinner = new Counter('race_winner');
export const barrierLate = new Counter('barrier_late');
