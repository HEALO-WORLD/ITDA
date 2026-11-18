// API 호출. 요청마다 api 태그를 붙여 API별 응답 시간·에러를 따로 본다
import http from 'k6/http';
import { BASE_URL, PASSWORD, REQ_TIMEOUT } from './config.js';
import { businessConflict } from './metrics.js';

// 409(슬롯 충돌·이미 처리됨 등 업무상 거절)는 실패로 세지 않는다. 대신 business_conflict로 따로 센다
http.setResponseCallback(http.expectedStatuses({ min: 200, max: 399 }, 409));

function params(token, api, extraTags) {
    const headers = { 'Content-Type': 'application/json' };
    if (token) headers.Authorization = `Bearer ${token}`;
    return { headers, tags: Object.assign({ api }, extraTags || {}), timeout: REQ_TIMEOUT };
}

function request(method, path, body, token, api, extraTags) {
    const res = http.request(method, `${BASE_URL}${path}`, body ? JSON.stringify(body) : null,
        params(token, api, extraTags));
    if (res.status === 409) businessConflict.add(1, { api });
    return res;
}

// 여러 요청을 같은 순간에 보낸다 (중복 클릭, 상태 경합)
export function batch(reqs) {
    const list = reqs.map((r) => ({
        method: r.method,
        url: `${BASE_URL}${r.path}`,
        body: r.body ? JSON.stringify(r.body) : null,
        params: params(r.token, r.api, r.tags),
    }));
    const res = http.batch(list);
    res.forEach((x, i) => { if (x.status === 409) businessConflict.add(1, { api: reqs[i].api }); });
    return res;
}

export function data(res) {
    try {
        const d = res.json('data');
        return d === undefined ? null : d;
    } catch (e) {
        return null;
    }
}

export function ok(res) {
    return res.status >= 200 && res.status < 300;
}

export function uuid() {
    return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
        const r = (Math.random() * 16) | 0;
        return (c === 'x' ? r : (r & 0x3) | 0x8).toString(16);
    });
}

// ---------------------------------------------------------------- 인증
export function login(loginId, tags) {
    return request('POST', '/api/v1/auth/login', { loginId, password: PASSWORD }, null, 'login', tags);
}
export function reissue(refreshToken, tags) {
    return request('POST', '/api/v1/auth/reissue', { refreshToken }, null, 'reissue', tags);
}
export function me(token, tags) {
    return request('GET', '/api/v1/users/me', null, token, 'me', tags);
}

// ---------------------------------------------------------------- 원무직원
export function partnerships(token, tags) {
    return request('GET', '/api/v1/partnerships/me?status=ACTIVE', null, token, 'partnerships', tags);
}
export function examTypes(token, tags) {
    return request('GET', '/api/v1/exam-types?page=0&size=100', null, token, 'exam_types', tags);
}
export function searchSlots(token, institutionId, examTypeId, day, tags) {
    const q = [
        'view=SEARCH', 'status=AVAILABLE', 'page=0', 'size=100',
        `institutionId=${institutionId}`, `examTypeId=${examTypeId}`,
        `from=${encodeURIComponent(`${day}T00:00:00+09:00`)}`,
        `to=${encodeURIComponent(`${day}T23:59:59+09:00`)}`,
    ].join('&');
    return request('GET', `/api/v1/slots?${q}`, null, token, 'search_slots', tags);
}
export function referralBody(ctx, examTypeId, examInstitutionId, slot, patientName, idempotencyKey) {
    const day = slot.startsAt.slice(0, 10);
    return {
        referringInstitutionId: ctx.institutionId,
        examTypeId,
        patientName,
        idempotencyKey,
        reservation: {
            slotId: slot.id,
            examInstitutionId,
            equipmentId: slot.equipmentId,
            examDate: `${day}T00:00:00`,
            startTime: slot.startsAt.slice(0, 19),
            endTime: slot.endsAt.slice(0, 19),
        },
    };
}
export function createReferral(token, body, tags) {
    return request('POST', '/api/v1/referrals', body, token, 'create_referral', tags);
}
export function createReferralReq(token, body, tags) {
    return { method: 'POST', path: '/api/v1/referrals', body, token, api: 'create_referral', tags };
}
export function getReferral(token, id, tags) {
    return request('GET', `/api/v1/referrals/${id}`, null, token, 'get_referral', tags);
}
export function cancelReservation(token, reservationId, tags) {
    return request('PATCH', `/api/v1/referrals/reservations/${reservationId}/cancel`, null, token, 'cancel', tags);
}
export function cancelReq(token, reservationId, tags) {
    return { method: 'PATCH', path: `/api/v1/referrals/reservations/${reservationId}/cancel`, token, api: 'cancel', tags };
}

// ---------------------------------------------------------------- 검사관리자
export function examinations(token, page, size, tags) {
    return request('GET', `/api/v1/examinations?page=${page}&size=${size}`, null, token, 'examinations', tags);
}
export function changeExamStatus(token, examinationId, status, tags) {
    const api = status === 'IN_PROGRESS' ? 'exam_start' : 'exam_complete';
    return request('PATCH', `/api/v1/examinations/${examinationId}/status`, { status }, token, api, tags);
}
export function startExamReq(token, examinationId, tags) {
    return { method: 'PATCH', path: `/api/v1/examinations/${examinationId}/status`, body: { status: 'IN_PROGRESS' },
        token, api: 'exam_start', tags };
}
export function registerResult(token, examinationId, tags) {
    return request('POST', `/api/v1/examinations/${examinationId}/results`,
        { summary: '특이 소견 없음 (k6 부하테스트)', notes: null }, token, 'result', tags);
}
export function equipmentsOf(token, institutionId, tags) {
    return request('GET', `/api/v1/institutions/${institutionId}/equipments?page=0&size=100`, null, token, 'equipments', tags);
}

// 측정 대상 API (응답 시간 기준을 거는 목록)
// 테스트마다 실제로 호출하는 API에만 기준을 건다. 표본이 없는 기준은 아무것도 재지 않고 통과로 나오기 때문이다
export const AUTH_APIS = ['login', 'reissue'];
export const REFERRER_APIS = ['partnerships', 'search_slots', 'create_referral', 'get_referral', 'cancel'];
export const LAB_POLL_APIS = ['examinations'];
export const LAB_PROCESS_APIS = ['exam_start', 'exam_complete', 'result'];
export const MEASURED_APIS = [...AUTH_APIS, ...REFERRER_APIS, ...LAB_POLL_APIS, ...LAB_PROCESS_APIS];
