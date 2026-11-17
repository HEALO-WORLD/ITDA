// 내구성: 하루 전체(07:40~18:30)를 실제 속도로 재생한다 (약 11시간)
//   검사관리자 토큰 재발급, 점심 새로고침 중단, 오늘 검사 240건의 시작·완료·결과 등록이 모두 들어간다
//   볼 것: 시간이 지나며 응답 시간·메모리·커넥션 수가 늘어나는지, Outbox·Kafka 처리가 밀리는지
import { replayOptions, replaySetup, runLabManager, runReferrer } from '../lib/replay.js';

const START = '07:40:00';
const END = '18:30:00';

export const options = replayOptions(START, END);
export function setup() { return replaySetup(START, END); }
export function referrers(data) { runReferrer(data); }
export function labManagers(data) { runLabManager(data); }
