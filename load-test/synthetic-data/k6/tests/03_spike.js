// 스파이크: 평시로 돌다가 10초 안에 원무직원이 한꺼번에 인기 슬롯으로 몰린 뒤, 회복되는지 본다
//   평시(base_before, PRE_SEC) → 급증(spike: 원무직원 80명 x SPIKE_X가 10초 안에 인기 슬롯 세션 시작)
//   → 회복 관찰(base_after, RECOVERY_SEC). 평시·회복 구간은 같은 도착률이다
//   -e SPIKE_X=5 -e BASE_RATE=0.5 -e PRE_SEC=120 -e RECOVERY_SEC=300
//
// 판정: 회복 구간(base_after)의 API 응답 시간이 기준 안으로 돌아오는가. 급증 구간 자체는 기준을 걸지 않는다
// 인기 슬롯은 수가 적어 대부분 reservation_failed(슬롯 경합)로 끝나는 것이 정상이다
import exec from 'k6/execution';
import { sleep } from 'k6';
import { managers, referrers, sessions } from '../lib/data.js';
import { labPollIteration, referrerSession, setupExamTypes, setupManagerCtxs, setupReferrerCtxs } from '../lib/flows.js';
import { LAB_POLL_APIS, REFERRER_APIS } from '../lib/api.js';
import { CONFIRM_P95_MS, P95_MS } from '../lib/config.js';

const SPIKE_X = Number(__ENV.SPIKE_X || 5);
const BASE_RATE = Number(__ENV.BASE_RATE || 0.5);
const PRE_SEC = Number(__ENV.PRE_SEC || 120);
const RECOVERY_SEC = Number(__ENV.RECOVERY_SEC || 300);
const SPIKE_VUS = referrers.length * SPIKE_X;
const TOTAL_SEC = PRE_SEC + RECOVERY_SEC;
// 상태 조회는 사용자 행동 수준(1초)으로 둔다. 정밀한 확정 시간은 after-test.sh가 DB의 생성·확정 시각으로 잰다
const POLL_MS = Number(__ENV.STATUS_POLL_MS || 1000);

// 정수 도착률로 바꾼다 (0.5/s → 30/m)
const base = { rate: Math.round(BASE_RATE * 60), timeUnit: '1m' };

const t = {
    http_req_failed: ['rate==0'],
    flow_error: ['count==0'],
    idempotency_violation: ['count==0'],
    [`reservation_confirm_ms{scenario:base_after}`]: [`p(95)<${CONFIRM_P95_MS}`],
    // 판정 아님: 구간별 확정 시간을 요약에 따로 보이기 위한 항목
    'reservation_confirm_ms{scenario:base_before}': ['max>=0'],
    'reservation_confirm_ms{scenario:spike}': ['max>=0'],
};
// 회복 구간은 원무직원 API로 판정한다 (로그인은 준비 단계에서만 하고, 검사관리자는 별도 시나리오)
// 취소는 회복 구간에 표본이 거의 없어(세션 수 x 취소 5% x 2시간 전 조건) 전체 기간으로 판정한다
REFERRER_APIS.filter((a) => a !== 'cancel')
    .forEach((a) => { t[`http_req_duration{api:${a},scenario:base_after}`] = [`p(95)<${P95_MS}`]; });
t['http_req_duration{api:cancel}'] = [`p(95)<${P95_MS}`];
t.reservation_canceled = ['count>0'];   // 취소가 0건이면 위 판정이 표본 없이 통과하므로 실패로 본다
// 검사관리자 접수 목록은 급증 구간을 포함한 전체 기간으로 판정한다 (급증에 휩쓸려 느려지는지)
LAB_POLL_APIS.forEach((a) => { t[`http_req_duration{api:${a},scenario:lab_managers}`] = [`p(95)<${P95_MS}`]; });

export const options = {
    setupTimeout: '10m',
    scenarios: {
        base_before: {
            executor: 'constant-arrival-rate', exec: 'baseLoad', ...base,
            duration: `${PRE_SEC}s`, preAllocatedVUs: 20, maxVUs: 200,
        },
        spike: {
            executor: 'per-vu-iterations', exec: 'spike', startTime: `${PRE_SEC}s`,
            vus: SPIKE_VUS, iterations: 1, maxDuration: `${RECOVERY_SEC}s`,
        },
        base_after: {
            executor: 'constant-arrival-rate', exec: 'baseLoad', ...base, startTime: `${PRE_SEC}s`,
            duration: `${RECOVERY_SEC}s`, preAllocatedVUs: 20, maxVUs: 200,
        },
        lab_managers: {
            executor: 'per-vu-iterations', exec: 'labManagers',
            vus: managers.length, iterations: 1, maxDuration: `${TOTAL_SEC + 60}s`,
        },
    },
    thresholds: t,
};

const popular = sessions.filter((s) => s.slot_type === 'popular');
const normal = sessions.filter((s) => s.slot_type === 'normal');

export function setup() {
    return {
        examTypeIds: setupExamTypes(),
        referrerCtxs: setupReferrerCtxs(),
        managerCtxs: setupManagerCtxs(),
        endAtMs: Date.now() + TOTAL_SEC * 1000,
    };
}

function run(row, data) {
    referrerSession(row, data.referrerCtxs[row.user_id], data.examTypeIds,
        { scale: 1, manageAuth: false, pollMs: POLL_MS, tags: { pattern: row.behavior_pattern } });
}

export function baseLoad(data) {
    run(normal[Math.floor(Math.random() * normal.length)], data);
}

export function spike(data) {
    sleep(Math.random() * 10);   // 10초 안에 몰린다
    run(popular[exec.scenario.iterationInTest % popular.length], data);
}

export function labManagers(data) {
    const mg = managers[exec.scenario.iterationInTest];
    const ctx = data.managerCtxs[mg.lab_id];
    sleep(Math.random() * 30);
    while (Date.now() < data.endAtMs) labPollIteration(ctx, {});
}
