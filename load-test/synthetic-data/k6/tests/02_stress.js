// 스트레스: 세션 도착률을 단계별로 올려 처음 기준이 깨지는 지점을 찾는다
//   기본 단계: 초당 세션 1 → 5 → 10 → 20 → 40건 (세션 1건 ≈ API 6회), 단계당 2분
//   -e LEVELS=1,5,10,20,40 -e STAGE_MIN=2 -e MAX_VUS=1500
//   하나만 길게 볼 때: -e LEVELS=20 -e STAGE_MIN=10
//
// 세션은 CSV 240건을 무작위로 골라 쓰고, 원무직원 계정은 VU끼리 같이 쓴다 (준비 단계에서 한 번 로그인한 토큰)
// 목표일은 앞으로 SPREAD_BIZ_DAYS(기본 55)영업일 안에서 흩뜨린다. CSV 규칙(최대 10영업일)대로면 ×10부터 슬롯이 바닥나
// 서버 한계가 아니라 슬롯 재고 한계를 재게 된다 (시드는 --ahead-days 90으로 슬롯을 만들어 둔다)
// 확정 시간은 k6 지표(1초 단위 조회)보다 after-test.sh의 DB 기준 값(밀리초)을 본다
// 기준이 깨지는 게 정상인 테스트라 중단하지 않고, 결과는 Grafana에서 단계별 시계열로 본다
import exec from 'k6/execution';
import { sleep } from 'k6';
import { managers, sessions } from '../lib/data.js';
import { labPollIteration, referrerSession, setupExamTypes, setupManagerCtxs, setupReferrerCtxs } from '../lib/flows.js';
import { thresholds } from '../lib/replay.js';
import { LAB_POLL_APIS, REFERRER_APIS } from '../lib/api.js';

const LEVELS = (__ENV.LEVELS || '1,5,10,20,40').split(',').map(Number);
const STAGE_MIN = Number(__ENV.STAGE_MIN || 2);
const MAX_VUS = Number(__ENV.MAX_VUS || 1500);
const RAMP_SEC = 10;
// 목표일을 앞으로 SPREAD_BIZ_DAYS영업일 안에서 흩뜨린다 (시드 슬롯 범위 90일 ≈ 영업일 60일 안쪽으로)
const SPREAD_BIZ_DAYS = Number(__ENV.SPREAD_BIZ_DAYS || 55);
// 상태 조회는 사용자 행동 수준(1초)으로 둔다. 250ms로 줄였더니 조회가 전체 요청의 83%가 되어 측정 대상을 덮었다
// 정밀한 확정 시간은 after-test.sh가 DB의 생성·확정 시각으로 잰다
const POLL_MS = Number(__ENV.STATUS_POLL_MS || 1000);
const STAGE_SEC = STAGE_MIN * 60 + RAMP_SEC;
const TOTAL_SEC = LEVELS.length * STAGE_SEC;

// 단계별로 처음 깨지는 지점을 보도록 요약에 단계(stage) 태그별 수치를 보인다 (판정 아님)
const stageTrends = {};
LEVELS.forEach((lv) => {
    stageTrends[`reservation_confirm_ms{stage:x${lv}}`] = ['max>=0'];
    stageTrends[`http_req_duration{api:create_referral,stage:x${lv}}`] = ['max>=0'];
    stageTrends[`http_req_failed{stage:x${lv}}`] = ['rate>=0'];
    stageTrends[`no_slot_available{stage:x${lv}}`] = ['count>=0'];
});

export const options = {
    setupTimeout: '10m',
    scenarios: {
        referrers: {
            executor: 'ramping-arrival-rate', exec: 'referrers',
            startRate: LEVELS[0], timeUnit: '1s',
            preAllocatedVUs: 100, maxVUs: MAX_VUS,
            stages: LEVELS.flatMap((lv) => [
                { target: lv, duration: `${RAMP_SEC}s` },
                { target: lv, duration: `${STAGE_MIN}m` },
            ]),
        },
        lab_managers: {
            executor: 'per-vu-iterations', exec: 'labManagers',
            vus: managers.length, iterations: 1, maxDuration: `${TOTAL_SEC + 60}s`,
        },
    },
    // 로그인은 준비 단계에서 한 번만 하고, 검사관리자는 새로고침만 하므로 그 API에만 기준을 건다
    thresholds: thresholds([...REFERRER_APIS, ...LAB_POLL_APIS], stageTrends),
};

export function setup() {
    return {
        examTypeIds: setupExamTypes(),
        referrerCtxs: setupReferrerCtxs(),
        managerCtxs: setupManagerCtxs(),
        startMs: Date.now(),
        endAtMs: Date.now() + TOTAL_SEC * 1000,
    };
}

export function referrers(data) {
    const row = sessions[Math.floor(Math.random() * sessions.length)];
    const ctx = data.referrerCtxs[row.user_id];
    // 세션이 시작된 단계로 태그를 붙인다 (단계 경계를 넘겨 끝나는 세션도 시작 단계로 센다)
    const i = Math.min(LEVELS.length - 1, Math.floor((Date.now() - data.startMs) / 1000 / STAGE_SEC));
    referrerSession(row, ctx, data.examTypeIds,
        { scale: 1, manageAuth: false, pollMs: POLL_MS, spreadBizDays: SPREAD_BIZ_DAYS,
            tags: { pattern: row.behavior_pattern, stage: `x${LEVELS[i]}` } });
}

export function labManagers(data) {
    const mg = managers[exec.scenario.iterationInTest];
    const ctx = data.managerCtxs[mg.lab_id];
    sleep(Math.random() * 30);
    while (Date.now() < data.endAtMs) labPollIteration(ctx, {});
}
