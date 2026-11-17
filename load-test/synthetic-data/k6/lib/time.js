// 날짜·시각 계산. k6 컨테이너는 UTC라 한국 시간은 +9시간으로 직접 계산한다
import { sleep } from 'k6';

const KST_OFFSET_MS = 9 * 3600 * 1000;

export function kstToday() {
    return new Date(Date.now() + KST_OFFSET_MS).toISOString().slice(0, 10);
}

export function addDays(isoDate, n) {
    const d = new Date(`${isoDate}T00:00:00Z`);
    d.setUTCDate(d.getUTCDate() + n);
    return d.toISOString().slice(0, 10);
}

// 0=일 ... 6=토
export function dayOfWeek(isoDate) {
    return new Date(`${isoDate}T00:00:00Z`).getUTCDay();
}

export function isWeekday(isoDate) {
    const w = dayOfWeek(isoDate);
    return w >= 1 && w <= 5;
}

export function nextBizDay(isoDate) {
    let d = addDays(isoDate, 1);
    while (!isWeekday(d)) d = addDays(d, 1);
    return d;
}

export function nextSaturday(isoDate) {
    let d = addDays(isoDate, 1);
    while (dayOfWeek(d) !== 6) d = addDays(d, 1);
    return d;
}

// CSV의 목표일 규칙: BIZ+n (n번째 평일), NEXT_SAT (다음 토요일)
// 스트레스용 확장: BIZ+n+SAT (n번째 평일 뒤의 첫 토요일)
export function resolveDay(rule, today) {
    if (rule === 'NEXT_SAT') return nextSaturday(today);
    const m = /^BIZ\+(\d+)(\+SAT)?$/.exec(rule);
    if (!m) throw new Error(`알 수 없는 목표일 규칙: ${rule}`);
    let d = today;
    for (let k = Number(m[1]); k > 0; k--) d = nextBizDay(d);
    return m[2] ? nextSaturday(d) : d;
}

// 같은 요일 종류(평일/토요일)로 다음 날을 찾는다 (원하는 날짜가 없을 때 다음 날 검색)
export function nextSearchDay(isoDate) {
    return dayOfWeek(isoDate) === 6 ? addDays(isoDate, 7) : nextBizDay(isoDate);
}

export function hmsToSec(hms) {
    const [h, m, s] = hms.split(':').map(Number);
    return h * 3600 + m * 60 + (s || 0);
}

// 테스트 시작 시각(startAtMs) 기준으로, 재생 시각(sec, 하루 중 초)이 될 때까지 기다린다
export function sleepUntilReplay(startAtMs, windowStartSec, sec, scale) {
    sleepUntilMs(startAtMs + ((sec - windowStartSec) / scale) * 1000);
}

export function sleepUntilMs(targetMs) {
    const wait = targetMs - Date.now();
    if (wait > 0) sleep(wait / 1000);
}

export function nowLocalDateTime() {
    return new Date(Date.now() + KST_OFFSET_MS).toISOString().slice(0, 19);
}
