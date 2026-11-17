// 기준선: 확대 규모의 하루 일정을 실제 속도로 재생해 통과 기준을 충족하는지 본다
//   기본 08:45~10:00 (오픈 피크 포함, 약 1시간 15분 + 마무리)
//   점심 피크:  -e WINDOW_START=11:45 -e WINDOW_END=13:15
//   연결 확인:  -e TIME_SCALE=60 (대기 시간만 1/60로 줄인다. 부하도 그만큼 몰리므로 판정에 쓰지 않는다)
import { replayOptions, replaySetup, runLabManager, runReferrer } from '../lib/replay.js';

const START = '08:45:00';
const END = '10:00:00';

export const options = replayOptions(START, END);
export function setup() { return replaySetup(START, END); }
export function referrers(data) { runReferrer(data); }
export function labManagers(data) { runLabManager(data); }
