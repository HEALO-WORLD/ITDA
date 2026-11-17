// CSV 시각표 재생 (기준선·내구성 공통)
//   원무직원 80명, 검사관리자 10명이 각자 VU 하나를 맡아, 시간대(WINDOW_START~WINDOW_END)의 일정을 순서대로 실행한다
import exec from 'k6/execution';
import { CONFIRM_P95_MS, LOGIN_P95_MS, P95_MS, TIME_SCALE } from './config.js';
import { managers, referrers, sessions } from './data.js';
import {
    labManagerDay, newCtx, referrerSession, setupExamTypes, setupManagerCtxs, setupTodayExams,
} from './flows.js';
import { MEASURED_APIS } from './api.js';
import { hmsToSec, sleepUntilReplay } from './time.js';

// apis: 응답 시간 기준을 걸 API 목록 (기본: 전부. 기준선·내구성은 모든 API를 호출한다)
export function thresholds(apis, extra) {
    const t = {
        http_req_failed: ['rate==0'],
        reservation_confirm_ms: [`p(95)<${CONFIRM_P95_MS}`],
        flow_error: ['count==0'],
        idempotency_violation: ['count==0'],
    };
    (apis || MEASURED_APIS).forEach((a) => {
        t[`http_req_duration{api:${a}}`] = [`p(95)<${a === 'login' ? LOGIN_P95_MS : P95_MS}`];
    });
    return Object.assign(t, extra || {});
}

export function replayOptions(defaultStart, defaultEnd) {
    const startSec = hmsToSec(__ENV.WINDOW_START || defaultStart);
    const endSec = hmsToSec(__ENV.WINDOW_END || defaultEnd);
    // 마지막 세션이 끝날 때까지(확정 대기·후속 행동) 여유를 둔다
    const maxSec = Math.ceil((endSec - startSec) / TIME_SCALE) + 900;
    return {
        setupTimeout: '10m',
        scenarios: {
            referrers: {
                executor: 'per-vu-iterations', exec: 'referrers',
                vus: referrers.length, iterations: 1, maxDuration: `${maxSec}s`,
            },
            lab_managers: {
                executor: 'per-vu-iterations', exec: 'labManagers',
                vus: managers.length, iterations: 1, maxDuration: `${maxSec}s`,
            },
        },
        thresholds: thresholds(),
    };
}

export function replaySetup(defaultStart, defaultEnd) {
    const examTypeIds = setupExamTypes();
    const managerCtxs = setupManagerCtxs();
    const labExams = setupTodayExams(managerCtxs);
    const window = {
        startSec: hmsToSec(__ENV.WINDOW_START || defaultStart),
        endSec: hmsToSec(__ENV.WINDOW_END || defaultEnd),
        scale: TIME_SCALE,
        startAtMs: Date.now() + 5000,   // 모든 VU가 같은 기준 시각에서 출발
    };
    console.log(`[setup] 재생 ${__ENV.WINDOW_START || defaultStart}~${__ENV.WINDOW_END || defaultEnd}, 배속 x${TIME_SCALE}`);
    return { examTypeIds, labExams, window };
}

export function runReferrer(data) {
    const u = referrers[exec.scenario.iterationInTest];
    const w = data.window;
    const mine = sessions
        .filter((s) => s.user_id === u.user_id)
        .map((s) => Object.assign({}, s, { sec: hmsToSec(s.start_time) }))
        .filter((s) => s.sec >= w.startSec && s.sec < w.endSec)
        .sort((a, b) => a.sec - b.sec);
    const ctx = newCtx(u.login_id);
    mine.forEach((s) => {
        sleepUntilReplay(w.startAtMs, w.startSec, s.sec, w.scale);
        referrerSession(s, ctx, data.examTypeIds,
            { scale: w.scale, manageAuth: true, tags: { window: s.time_window, pattern: s.behavior_pattern } });
    });
}

export function runLabManager(data) {
    const mg = managers[exec.scenario.iterationInTest];
    labManagerDay(mg, data.labExams[mg.lab_id] || [], data.window, {});
}
