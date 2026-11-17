// 사용자 행동 흐름: 원무직원 세션, 검사관리자 하루, 준비(setup) 단계 공통 로직
import { sleep } from 'k6';
import * as api from './api.js';
import {
    CONFIRM_TIMEOUT_SEC, POLL_JITTER_SEC, POLL_PAGE_SIZE, POLL_SEC, STATUS_POLL_MS, TODAY, TOKEN_REFRESH_SEC,
} from './config.js';
import { equipments, managers, referrers, todayExams } from './data.js';
import * as m from './metrics.js';
import { hmsToSec, nextSearchDay, resolveDay, sleepUntilReplay } from './time.js';

const SETUP = { api: 'setup' };   // 준비 단계 요청은 측정에서 빼도록 api 태그를 덮어쓴다
const FINAL = ['CONFIRMED', 'FAILED', 'CANCELED', 'NEEDS_RESCHEDULE', 'EXPIRED'];

const SURNAMES = '김이박최정강조윤장임한오서신권황안송류전홍';
const GIVEN = '민서준지현우도윤하은수연영진호성재가경나다';
function pick(s) { return s[Math.floor(Math.random() * s.length)]; }
export function patientName() { return `부하테스트${pick(SURNAMES)}${pick(GIVEN)}${pick(GIVEN)}`; }

// ---------------------------------------------------------------- 인증
// ctx: { loginId, token, refresh, tokenAt, institutionId, labIds: {검사기관명: 기관 ID} }
export function newCtx(loginId) {
    return { loginId, token: null, refresh: null, tokenAt: 0, institutionId: null, labIds: null };
}

export function doLogin(ctx, tags) {
    const res = api.login(ctx.loginId, tags);
    const d = api.data(res);
    if (!api.ok(res) || !d || !d.accessToken) {
        m.flowError.add(1, { step: 'login' });
        return false;
    }
    ctx.token = d.accessToken;
    ctx.refresh = d.refreshToken;
    ctx.tokenAt = Date.now();
    if (!ctx.institutionId) {
        const meData = api.data(api.me(ctx.token, SETUP));
        ctx.institutionId = meData ? meData.institutionId : null;
    }
    return !!ctx.institutionId;
}

export function doReissue(ctx) {
    const res = api.reissue(ctx.refresh);
    const d = api.data(res);
    if (!api.ok(res) || !d || !d.accessToken) {
        m.flowError.add(1, { step: 'reissue' });
        return doLogin(ctx);
    }
    ctx.token = d.accessToken;
    ctx.refresh = d.refreshToken;
    ctx.tokenAt = Date.now();
    return true;
}

// ---------------------------------------------------------------- 준비 단계
export function setupExamTypes() {
    const ctx = newCtx(referrers[0].login_id);
    if (!doLogin(ctx, SETUP)) throw new Error('준비 단계 로그인 실패. 앱이 떠 있는지, 시드가 들어갔는지 확인하세요');
    const page = api.data(api.examTypes(ctx.token, SETUP));
    const map = {};
    (page ? page.contents : []).forEach((t) => { map[t.name] = t.id; });
    if (Object.keys(map).length === 0) throw new Error('검사 종류를 찾지 못했습니다');
    return map;
}

// 원무직원 전원 로그인 + 협력 검사기관 ID (스트레스·스파이크·동시성: 토큰을 VU끼리 같이 쓴다)
export function setupReferrerCtxs() {
    const ctxs = {};
    referrers.forEach((u) => {
        const ctx = newCtx(u.login_id);
        if (!doLogin(ctx, SETUP)) throw new Error(`로그인 실패: ${u.login_id}`);
        loadPartners(ctx, SETUP);
        ctxs[u.user_id] = ctx;
    });
    return ctxs;
}

export function setupManagerCtxs() {
    const ctxs = {};
    managers.forEach((mg) => {
        const ctx = newCtx(mg.login_id);
        if (!doLogin(ctx, SETUP)) throw new Error(`로그인 실패: ${mg.login_id}`);
        ctxs[mg.lab_id] = ctx;
    });
    return ctxs;
}

function loadPartners(ctx, tags) {
    const list = api.data(api.partnerships(ctx.token, tags)) || [];
    ctx.labIds = {};
    list.forEach((p) => { ctx.labIds[p.partnerInstitutionName] = p.partnerInstitutionId; });
}

// 오늘 검사 예정(today_examinations.csv) 행마다 실제 검사 ID를 찾는다
//   장비 이름(equipments.csv) → 장비 ID, (장비 ID, 시작 시각) → 검사 ID
export function setupTodayExams(managerCtxs) {
    const byLab = {};
    let missing = 0;
    managers.forEach((mg) => {
        const ctx = managerCtxs[mg.lab_id];
        const eqPage = api.data(api.equipmentsOf(ctx.token, ctx.institutionId, SETUP));
        const eqIdByName = {};
        (eqPage ? eqPage.contents : []).forEach((e) => { eqIdByName[e.name] = e.id; });

        const examIdByKey = {};
        const want = todayExams.filter((t) => t.lab_id === mg.lab_id).length;
        let found = 0;
        for (let page = 0; page < 30 && found < want; page++) {
            const p = api.data(api.examinations(ctx.token, page, 100, SETUP));
            if (!p || p.contents.length === 0) break;
            p.contents.forEach((x) => {
                if (x.startTime && x.startTime.startsWith(TODAY) && x.status === 'SCHEDULED') {
                    examIdByKey[`${x.equipmentId}|${x.startTime.slice(11, 16)}`] = x.examinationId;
                    found++;
                }
            });
        }
        byLab[mg.lab_id] = todayExams.filter((t) => t.lab_id === mg.lab_id).map((t) => {
            const eq = equipments.find((e) => e.equipment_code === t.equipment_code);
            const examinationId = examIdByKey[`${eqIdByName[eq.equipment_name]}|${t.slot_start}`] || null;
            if (!examinationId) missing++;
            return Object.assign({}, t, { examinationId });
        });
    });
    if (missing > 0) {
        console.warn(`[setup] 오늘 검사 ${missing}건의 검사 ID를 찾지 못했습니다. 시드 기준일(${TODAY})과 오늘 날짜가 같은지 확인하세요`);
    }
    return byLab;
}

// ---------------------------------------------------------------- 원무직원 세션
function think(sec, scale) {
    if (sec > 0) sleep(sec / scale);
}

function chooseSlot(items, targetTime, avoidId) {
    const avail = items.filter((s) => s.status === 'AVAILABLE' && s.id !== avoidId);
    if (avail.length === 0) return { slot: null, exact: false };
    const exact = avail.find((s) => s.startsAt.slice(11, 16) === targetTime);
    if (exact) return { slot: exact, exact: true };
    const target = hmsToSec(`${targetTime}:00`);
    avail.sort((a, b) => Math.abs(hmsToSec(a.startsAt.slice(11, 19)) - target)
        - Math.abs(hmsToSec(b.startsAt.slice(11, 19)) - target));
    return { slot: avail[0], exact: false };
}

// 슬롯 검색: 원하는 시간이 없으면 search_count 안에서 다음 날을 다시 찾는다
function findSlot(ctx, row, labInstId, examTypeId, searchCount, scale, tags, avoidId, dayRule) {
    let day = resolveDay(dayRule || row.target_day_rule, TODAY);
    let fallback = null;
    for (let i = 0; i < searchCount; i++) {
        const res = api.searchSlots(ctx.token, labInstId, examTypeId, day, tags);
        const page = api.data(res);
        const { slot, exact } = chooseSlot(page ? page.items : [], row.target_slot_time, avoidId);
        if (exact) return slot;
        if (slot && !fallback) fallback = slot;
        if (i < searchCount - 1) {
            day = nextSearchDay(day);
            think(Number(row.think_time_sec), scale);
        }
    }
    return fallback;
}

// 생성 → 확정·실패까지 상태 조회. 사용자가 보는 횟수(statusChecks) 뒤에도 끝나지 않았으면 결과를 알 때까지 조회를 이어간다
function waitResult(ctx, referralId, statusChecks, tags, pollMs) {
    const t0 = Date.now();
    let last = null;
    for (let n = 0; ; n++) {
        if (Date.now() - t0 > CONFIRM_TIMEOUT_SEC * 1000) {
            m.unresolved.add(1, tags);
            return last;
        }
        sleep((pollMs || STATUS_POLL_MS) / 1000);
        const extra = n >= statusChecks ? { api: 'get_referral_extra' } : {};
        const d = api.data(api.getReferral(ctx.token, referralId, Object.assign({}, tags, extra)));
        if (!d) continue;
        last = d;
        if (FINAL.includes(d.referralStatus)) {
            m.confirmMs.add(Date.now() - t0, tags);
            if (d.referralStatus === 'CONFIRMED') m.confirmed.add(1, tags); else m.holdFailed.add(1, tags);
            return d;
        }
    }
}

function submitReferral(ctx, body, createCount, tags) {
    if (createCount >= 2) {
        // 예약 버튼 두 번 클릭: 같은 멱등 키로 동시에 두 번 보낸다
        const res = api.batch([api.createReferralReq(ctx.token, body, tags), api.createReferralReq(ctx.token, body, tags)]);
        const ids = res.filter(api.ok).map((r) => (api.data(r) || {}).id).filter(Boolean);
        if (new Set(ids).size > 1) m.idempotencyViolation.add(1, tags);
        return ids.length ? res.find(api.ok) : res[0];
    }
    return api.createReferral(ctx.token, body, tags);
}

// 목표일 규칙을 넓은 범위로 흩뜨린다 (스트레스: 높은 단계에서 같은 날 슬롯이 바닥나지 않게)
//   평일 규칙 → BIZ+1~spread 중 무작위, 토요일 규칙 → 앞으로 spread/5주 안의 토요일 중 무작위
function spreadDayRule(rule, spread) {
    if (!spread) return null;
    if (rule === 'NEXT_SAT') {
        const weeks = Math.max(1, Math.floor(spread / 5));
        return `BIZ+${5 * Math.floor(Math.random() * weeks)}+SAT`;
    }
    return `BIZ+${1 + Math.floor(Math.random() * spread)}`;
}

// opts: { scale, manageAuth (CSV의 auth_action을 따를지), tags,
//         pollMs (상태 조회 간격, 기본 STATUS_POLL_MS), spreadBizDays (목표일을 앞으로 n영업일 안에서 흩뜨림) }
export function referrerSession(row, ctx, examTypeIds, opts) {
    const scale = opts.scale || 1;
    const tags = opts.tags || {};

    if (opts.manageAuth) {
        if (row.auth_action === 'login' || !ctx.token) {
            if (!doLogin(ctx)) return;
        } else if (row.auth_action === 'reissue') {
            doReissue(ctx);
        }
    }

    // 1. 협력 검사기관 조회
    const partnersRes = api.partnerships(ctx.token, tags);
    const partners = api.data(partnersRes) || [];
    const labInstId = (partners.find((p) => p.partnerInstitutionName === row.target_lab_name) || {}).partnerInstitutionId;
    const examTypeId = examTypeIds[row.exam_type];
    if (!labInstId || !examTypeId) {
        m.flowError.add(1, { step: 'mapping' });
        return;
    }
    think(Number(row.think_time_sec), scale);

    // 2. 슬롯 검색
    const dayRule = spreadDayRule(row.target_day_rule, opts.spreadBizDays);
    const slot = findSlot(ctx, row, labInstId, examTypeId, Number(row.search_count), scale, tags, null, dayRule);
    if (!slot) {
        m.noSlotAvailable.add(1, tags);
        return;
    }
    think(Number(row.think_time_sec), scale);

    // 3. 의뢰 + 예약 생성
    const body = api.referralBody(ctx, examTypeId, labInstId, slot, patientName(), api.uuid());
    const created = submitReferral(ctx, body, Number(row.create_count), tags);
    const ref = api.data(created);
    if (!api.ok(created) || !ref || !ref.id) {
        if (created.status !== 409) m.flowError.add(1, { step: 'create' });
        return;
    }

    // 4. 상태 조회
    const result = waitResult(ctx, ref.id, Number(row.status_check_count), tags, opts.pollMs);
    const status = result ? result.referralStatus : null;

    // 5. 후속 행동
    if (row.post_action === 'cancel' && status === 'CONFIRMED') {
        think(Number(row.post_action_delay_sec), scale);
        const r = api.cancelReservation(ctx.token, result.currentReservationId, tags);
        if (api.ok(r)) m.canceled.add(1, tags);
    } else if (row.post_action === 'rebook' && status === 'FAILED') {
        // 첫 예약이 실패한 의뢰는 재예약할 수 없어 다른 슬롯으로 새 의뢰를 만든다
        think(Number(row.post_action_delay_sec), scale);
        const other = findSlot(ctx, row, labInstId, examTypeId, 1, scale, tags, slot.id, dayRule);
        if (!other) {
            m.noSlotAvailable.add(1, tags);
            return;
        }
        const body2 = api.referralBody(ctx, examTypeId, labInstId, other, patientName(), api.uuid());
        const r2 = api.createReferral(ctx.token, body2, tags);
        const ref2 = api.data(r2);
        if (api.ok(r2) && ref2) {
            m.rebooked.add(1, tags);
            waitResult(ctx, ref2.id, 2, tags, opts.pollMs);
        }
    }
}

// ---------------------------------------------------------------- 검사관리자
function pollOnce(ctx, tags) {
    api.examinations(ctx.token, 0, POLL_PAGE_SIZE, tags);
}

function keepTokenFresh(ctx) {
    if (Date.now() - ctx.tokenAt > TOKEN_REFRESH_SEC * 1000) doReissue(ctx);
}

// 하루 재생: 접속 시각에 로그인, 새로고침 주기마다 접수 목록 조회, 오늘 검사 일정에 맞춰 시작·완료·결과 등록
// window: { startSec, endSec, startAtMs, scale }
export function labManagerDay(mg, exams, window, tags) {
    const loginSec = Math.max(hmsToSec(mg.login_time), window.startSec);
    const logoutSec = Math.min(hmsToSec(mg.logout_time), window.endSec);
    if (loginSec >= logoutSec) return;
    const pauseLunch = mg.lunch_behavior === 'pause';

    const events = [];
    for (let t = loginSec + 5; t < logoutSec; t += POLL_SEC + (Math.random() * 2 - 1) * POLL_JITTER_SEC) {
        if (pauseLunch && t >= 12 * 3600 && t < 13 * 3600) continue;
        events.push({ at: t, kind: 'poll' });
    }
    exams.forEach((x) => {
        if (!x.examinationId) return;
        [['start_at', 'IN_PROGRESS'], ['complete_at', 'COMPLETED'], ['result_at', 'RESULT']].forEach(([col, kind]) => {
            const at = hmsToSec(x[col]);
            if (at >= loginSec && at < logoutSec) events.push({ at, kind, exam: x });
        });
    });
    events.sort((a, b) => a.at - b.at);

    sleepUntilReplay(window.startAtMs, window.startSec, loginSec, window.scale);
    const ctx = newCtx(mg.login_id);
    if (!doLogin(ctx)) return;

    events.forEach((ev) => {
        sleepUntilReplay(window.startAtMs, window.startSec, ev.at, window.scale);
        keepTokenFresh(ctx);
        if (ev.kind === 'poll') {
            pollOnce(ctx, tags);
        } else if (ev.kind === 'RESULT') {
            api.registerResult(ctx.token, ev.exam.examinationId, tags);
        } else {
            api.changeExamStatus(ctx.token, ev.exam.examinationId, ev.kind, tags);
        }
    });
}

// 스트레스·스파이크용: 접수 목록 새로고침만 반복한다 (한 번 호출 + 다음 주기까지 대기)
export function labPollIteration(ctx, tags) {
    keepTokenFresh(ctx);
    pollOnce(ctx, tags);
    sleep(POLL_SEC + (Math.random() * 2 - 1) * POLL_JITTER_SEC);
}
