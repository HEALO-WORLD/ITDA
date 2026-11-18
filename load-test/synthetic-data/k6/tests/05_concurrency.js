// 동시성: 같은 순간에 요청을 보내 정합성이 깨지지 않는지 본다 (약 6분)
//
// A. 같은 슬롯 동시 예약 (same_slot)
//    비어 있는 인기 슬롯(가람영상의학센터 MRI 08:00) 하나에 서로 다른 의원 SAME_SLOT_VUS명이 같은 순간 의뢰한다
//    ROUNDS번 반복. 라운드마다 확정은 정확히 1건, 나머지는 FAILED(슬롯 경합)여야 한다
// B. 같은 멱등 키 동시 전송 (idempotency)
//    같은 키로 의뢰 생성을 동시에 두 번 보낸다. 의뢰 ID는 하나만 나와야 한다 (나머지는 같은 ID 또는 409)
// C. 취소 vs 검사 시작 (cancel_vs_start)
//    확정된 예약에 원무직원 취소와 검사관리자 검사 시작을 같은 순간 보낸다 (RACE_PAIRS건)
//    정확히 한쪽만 성공하고, 최종 상태가 성공한 쪽과 일치해야 한다
//
// 최종 판정은 테스트 후 after-test.sh의 DB 정합성 검사까지 통과해야 한다 (200을 주고 DB가 틀린 경우를 잡는다)
// 콘솔에 barrier_late가 많으면 동시 전송 조건이 깨진 것이므로 k6 머신 성능을 올리거나 VU 수를 줄인다
import exec from 'k6/execution';
import { sleep } from 'k6';
import * as api from '../lib/api.js';
import { partnerships, referrers } from '../lib/data.js';
import { patientName, setupExamTypes, setupManagerCtxs, setupReferrerCtxs } from '../lib/flows.js';
import * as m from '../lib/metrics.js';
import { STATUS_POLL_MS, TODAY } from '../lib/config.js';
import { nextBizDay, sleepUntilMs } from '../lib/time.js';

const LAB = __ENV.RACE_LAB || 'LAB01';
const LAB_NAME = '가람영상의학센터';
const SAME_SLOT_VUS = Number(__ENV.SAME_SLOT_VUS || 30);
const ROUNDS = Number(__ENV.ROUNDS || 5);
const ROUND_GAP_SEC = Number(__ENV.ROUND_GAP_SEC || 30);
const IDEM_PAIRS = Number(__ENV.IDEM_PAIRS || 20);
const RACE_PAIRS = Number(__ENV.RACE_PAIRS || 30);
const RACE_PREP_SEC = Number(__ENV.RACE_PREP_SEC || 90);
const LATE_MS = Number(__ENV.LATE_MS || 200);

const A_START = 15;
const B_START = A_START + ROUNDS * ROUND_GAP_SEC + 20;
const C_START = B_START + 40;

// 이 검사기관과 협력 관계가 있는 의원 (A·C에서 VU마다 다른 의원을 쓴다)
const partnerUsers = (() => {
    const orgs = new Set(partnerships.filter((p) => p.lab_id === LAB).map((p) => p.org_id));
    return referrers.filter((u) => orgs.has(u.org_id));
})();

const thresholds = {
    flow_error: ['count==0'],
    idempotency_violation: ['count==0'],
    state_race_violation: ['count==0'],
    http_req_failed: ['rate==0'],
};
for (let r = 1; r <= ROUNDS; r++) thresholds[`same_slot_confirmed{round:${r}}`] = ['count==1'];
// 판정은 아니고 요약에 승자별 건수를 보이기 위한 항목
thresholds['race_winner{winner:cancel}'] = ['count>=0'];
thresholds['race_winner{winner:start}'] = ['count>=0'];

export const options = {
    setupTimeout: '10m',
    scenarios: {
        same_slot: {
            executor: 'per-vu-iterations', exec: 'sameSlot', startTime: '0s',
            vus: Math.min(SAME_SLOT_VUS, partnerUsers.length), iterations: 1,
            maxDuration: `${A_START + ROUNDS * ROUND_GAP_SEC + 60}s`,
        },
        idempotency: {
            executor: 'shared-iterations', exec: 'idempotency', startTime: `${B_START}s`,
            vus: 5, iterations: IDEM_PAIRS, maxDuration: '60s',
        },
        cancel_vs_start: {
            executor: 'per-vu-iterations', exec: 'cancelVsStart', startTime: `${C_START}s`,
            vus: Math.min(RACE_PAIRS, partnerUsers.length), iterations: 1, maxDuration: `${RACE_PREP_SEC + 90}s`,
        },
    },
    thresholds,
};

function searchDay(ctx, labInstId, examTypeId, day) {
    const p = api.data(api.searchSlots(ctx.token, labInstId, examTypeId, day, { api: 'setup' }));
    return p ? p.items.filter((s) => s.status === 'AVAILABLE') : [];
}

export function setup() {
    const examTypeIds = setupExamTypes();
    const referrerCtxs = setupReferrerCtxs();
    const managerCtxs = setupManagerCtxs();
    const any = referrerCtxs[partnerUsers[0].user_id];
    const labInstId = any.labIds[LAB_NAME];

    // A용: 앞으로 평일의 MRI 08:00 슬롯 (시드가 비워 둔 인기 슬롯)
    const roundSlots = [];
    let day = TODAY;
    for (let i = 0; i < 15 && roundSlots.length < ROUNDS; i++) {
        day = nextBizDay(day);
        ['뇌 MRI', '척추 MRI'].forEach((name) => {
            searchDay(any, labInstId, examTypeIds[name], day)
                .filter((s) => s.startsAt.slice(11, 16) === '08:00')
                .forEach((s) => { if (roundSlots.length < ROUNDS) roundSlots.push({ slot: s, examTypeId: examTypeIds[name] }); });
        });
    }
    if (roundSlots.length < ROUNDS) throw new Error(`같은 슬롯 라운드용 빈 MRI 08:00 슬롯이 ${roundSlots.length}개뿐입니다 (필요 ${ROUNDS})`);

    const base = Date.now();
    return {
        examTypeIds, referrerCtxs, managerCtxs, labInstId, roundSlots,
        roundAt: roundSlots.map((_, r) => base + (A_START + r * ROUND_GAP_SEC) * 1000),
        raceAt: base + (C_START + RACE_PREP_SEC) * 1000,
    };
}

function waitFinal(ctx, referralId, timeoutSec) {
    const t0 = Date.now();
    while (Date.now() - t0 < timeoutSec * 1000) {
        sleep(STATUS_POLL_MS / 1000);
        const d = api.data(api.getReferral(ctx.token, referralId, { api: 'get_referral_extra' }));
        if (d && d.referralStatus !== 'PENDING') return d;
    }
    return null;
}

function late(targetMs, scenario) {
    if (Date.now() - targetMs > LATE_MS) {
        m.barrierLate.add(1, { scenario });
        console.warn(`[barrier] ${scenario} ${Date.now() - targetMs}ms 늦게 도착`);
    }
}

// ---------------------------------------------------------------- A. 같은 슬롯 동시 예약
export function sameSlot(data) {
    const u = partnerUsers[exec.scenario.iterationInTest];
    const ctx = data.referrerCtxs[u.user_id];
    data.roundSlots.forEach((rs, r) => {
        const round = String(r + 1);
        m.sameSlotConfirmed.add(0, { round });   // 확정이 0건이어도 지표가 생겨 count==1 판정에 걸리게 한다
        const body = api.referralBody(ctx, rs.examTypeId, data.labInstId, rs.slot, patientName(), api.uuid());
        sleepUntilMs(data.roundAt[r]);
        late(data.roundAt[r], 'same_slot');
        const res = api.createReferral(ctx.token, body, { round });
        const ref = api.data(res);
        if (!api.ok(res) || !ref) {
            m.flowError.add(1, { step: 'same_slot_create' });
            return;
        }
        const fin = waitFinal(ctx, ref.id, 30);
        if (!fin) m.unresolved.add(1, { round });
        else if (fin.referralStatus === 'CONFIRMED') m.sameSlotConfirmed.add(1, { round });
    });
}

// ---------------------------------------------------------------- B. 같은 멱등 키 동시 전송
export function idempotency(data) {
    const u = partnerUsers[exec.scenario.iterationInTest % partnerUsers.length];
    const ctx = data.referrerCtxs[u.user_id];
    const examTypeId = data.examTypeIds['흉부 CT'];
    let day = nextBizDay(nextBizDay(TODAY));
    let slots = [];
    for (let i = 0; i < 5 && slots.length === 0; i++) {
        slots = searchDay(ctx, data.labInstId, examTypeId, day).filter((s) => s.startsAt.slice(11, 13) >= '09');
        day = nextBizDay(day);
    }
    if (slots.length === 0) { m.noSlotAvailable.add(1); return; }
    const slot = slots[Math.floor(Math.random() * slots.length)];
    const body = api.referralBody(ctx, examTypeId, data.labInstId, slot, patientName(), api.uuid());
    const res = api.batch([api.createReferralReq(ctx.token, body), api.createReferralReq(ctx.token, body)]);
    const ids = res.filter(api.ok).map((r) => (api.data(r) || {}).id).filter(Boolean);
    const unexpected = res.filter((r) => !api.ok(r) && r.status !== 409);
    if (new Set(ids).size !== 1 || unexpected.length > 0) {
        m.idempotencyViolation.add(1);
        console.warn(`[idempotency] 응답 ${res.map((r) => r.status).join(',')}, 의뢰 ID ${[...new Set(ids)].join(',')}`);
    }
}

// ---------------------------------------------------------------- C. 취소 vs 검사 시작
function findExamination(labCtx, reservationId) {
    for (let i = 0; i < 10; i++) {
        const p = api.data(api.examinations(labCtx.token, 0, 100, { api: 'setup' }));
        const x = p ? p.contents.find((e) => e.reservationId === reservationId) : null;
        if (x) return x;
        sleep(1);
    }
    return null;
}

export function cancelVsStart(data) {
    const u = partnerUsers[exec.scenario.iterationInTest];
    const ctx = data.referrerCtxs[u.user_id];
    const labCtx = data.managerCtxs[LAB];
    // 준비: 취소 기한(검사 2시간 전)에 걸리지 않는 앞으로의 일반 슬롯을 확정까지 잡는다
    // VU끼리 같은 슬롯을 두고 경합하지 않도록 CT 검사 종류와 날짜(BIZ+2~6)를 흩뜨린다
    let confirmedRef = null;
    const ctTypes = ['흉부 CT', '복부 CT'];
    for (let attempt = 0; attempt < 10 && !confirmedRef; attempt++) {
        const examTypeId = data.examTypeIds[ctTypes[(exec.vu.idInTest + attempt) % ctTypes.length]];
        let day = TODAY;
        for (let k = 2 + ((exec.vu.idInTest + attempt) % 5); k > 0; k--) day = nextBizDay(day);
        const slots = searchDay(ctx, data.labInstId, examTypeId, day).filter((s) => s.startsAt.slice(11, 13) >= '09');
        if (slots.length === 0) continue;
        const slot = slots[Math.floor(Math.random() * slots.length)];
        const res = api.createReferral(ctx.token,
            api.referralBody(ctx, examTypeId, data.labInstId, slot, patientName(), api.uuid()), { api: 'setup' });
        const ref = api.data(res);
        if (!api.ok(res) || !ref) continue;
        const fin = waitFinal(ctx, ref.id, 30);
        if (fin && fin.referralStatus === 'CONFIRMED') confirmedRef = fin;
    }
    if (!confirmedRef) {
        m.flowError.add(1, { step: 'race_prepare' });
        console.warn('[race] 준비 실패: 확정된 예약을 만들지 못했습니다');
        return;
    }
    const exam = findExamination(labCtx, confirmedRef.currentReservationId);
    if (!exam) {
        m.flowError.add(1, { step: 'race_find_exam' });
        console.warn(`[race] 준비 실패: 예약 ${confirmedRef.currentReservationId}의 검사를 목록에서 찾지 못했습니다`);
        return;
    }

    // 같은 순간 전송
    sleepUntilMs(data.raceAt);
    late(data.raceAt, 'cancel_vs_start');
    // 배치 안의 순서가 결과에 치우침을 주지 않도록 VU마다 보내는 순서를 바꾼다
    const cancelFirst = exec.vu.idInTest % 2 === 0;
    const reqs = [api.cancelReq(ctx.token, confirmedRef.currentReservationId), api.startExamReq(labCtx.token, exam.examinationId)];
    const res = api.batch(cancelFirst ? reqs : [reqs[1], reqs[0]]);
    const [c, s] = cancelFirst ? res : [res[1], res[0]];
    const cancelOk = api.ok(c);
    const startOk = api.ok(s);

    // 최종 상태 확인
    // 검사 목록은 취소된 의뢰의 검사를 보여주지 않는다. 취소가 이겼으면 목록에 없는 것이 정상이고,
    // 그 검사가 시작되지 않았는지는 after-test.sh의 DB 검사("취소된 의뢰인데 검사가 시작·완료됨")로 판정한다
    const ref = api.data(api.getReferral(ctx.token, confirmedRef.id, { api: 'get_referral_extra' }));
    const refStatus = ref ? ref.referralStatus : null;
    const after = cancelOk ? null : findExamination(labCtx, confirmedRef.currentReservationId);
    const examStatus = after ? after.status : null;

    let violation = cancelOk === startOk;   // 둘 다 성공하거나 둘 다 실패
    if (cancelOk && !startOk) {
        violation = violation || refStatus !== 'CANCELED';
        m.raceWinner.add(1, { winner: 'cancel' });
    } else if (startOk && !cancelOk) {
        violation = violation || refStatus !== 'CONFIRMED' || examStatus !== 'IN_PROGRESS';
        m.raceWinner.add(1, { winner: 'start' });
    }
    if (violation) {
        m.stateRaceViolation.add(1);
        console.warn(`[race] 취소 ${c.status}, 시작 ${s.status}, 의뢰 ${refStatus}, 검사 ${examStatus}`);
    }
}
