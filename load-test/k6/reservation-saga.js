// 예약 saga 부하 테스트
// POST /api/v1/referrals 한 건 = HoldRequested → SlotHeld → BookSlot → SlotBooked 한 바퀴
// 요청마다 다른 슬롯을 잡아 모두 확정까지 가게 한다 (충돌 없는 정상 흐름의 지연 측정)
//
// 실행: k6 run -e RATE=20 -e DURATION=3m load-test/k6/reservation-saga.js
// 먼저 load-test/sql의 reset 스크립트로 데이터를 초기화한다
import http from 'k6/http';
import { check, fail } from 'k6';
import exec from 'k6/execution';

const GATEWAY = __ENV.GATEWAY || 'http://localhost:8080';
const INSTITUTION = __ENV.INSTITUTION || 'http://localhost:8081';   // 개발용 토큰은 Gateway가 막아 직접 호출
const RATE = Number(__ENV.RATE || 20);          // 본 측정 초당 요청 수
const DURATION = __ENV.DURATION || '3m';

// load-test/sql/reset-slot.sql과 맞춘 값
const TOTAL_SLOTS = 10000;
const MAIN_OFFSET = 1000;                       // 워밍업은 0~999번, 본 측정은 1000번 슬롯부터
const SLOTS_PER_DAY = 20;
const BASE_DATE = Date.UTC(2026, 11, 15);       // 2026-12-15
const REQUESTER_INSTITUTION = '20000000-0000-0000-0000-000000000001';   // A의원
const EXAM_INSTITUTION = '20000000-0000-0000-0000-000000000003';        // B영상의학과
const EQUIPMENT = 'f0000000-0000-0000-0000-00000000e001';
const EXAM_TYPE = 'f0000000-0000-0000-0000-00000000a001';

const vus = Math.max(20, RATE * 2);

export const options = {
    scenarios: {
        warmup: {
            executor: 'constant-arrival-rate',
            rate: 5, timeUnit: '1s', duration: '30s',
            preAllocatedVUs: 10, maxVUs: 50,
            tags: { phase: 'warmup' },
            env: { OFFSET: '0' },
        },
        main: {
            executor: 'constant-arrival-rate',
            rate: RATE, timeUnit: '1s', duration: DURATION,
            startTime: '40s',                       // 워밍업 saga가 끝날 시간을 둔다
            preAllocatedVUs: vus, maxVUs: vus * 5,
            tags: { phase: 'main' },
            env: { OFFSET: String(MAIN_OFFSET) },
        },
    },
    thresholds: {
        'http_req_failed{phase:main}': ['rate<0.01'],
        'checks{phase:main}': ['rate>0.99'],
    },
    summaryTrendStats: ['avg', 'p(50)', 'p(95)', 'p(99)', 'max'],
};

export function setup() {
    const res = http.post(`${INSTITUTION}/internal/dev/tokens/REQUESTER_A`);
    if (res.status !== 200) {
        fail(`토큰 발급 실패 (${res.status}). institution-service의 DEV_TOKEN_ENABLED=true인지 확인`);
    }
    return { token: res.json('data.accessToken') };
}

export default function (data) {
    const n = Number(__ENV.OFFSET) + exec.scenario.iterationInTest;
    if (n >= TOTAL_SLOTS || (__ENV.OFFSET === '0' && n >= MAIN_OFFSET)) {
        fail(`슬롯 부족: ${n}번. RATE·DURATION을 줄이거나 reset-slot.sql의 슬롯 수를 늘린다`);
    }

    const res = http.post(`${GATEWAY}/api/v1/referrals`, JSON.stringify(body(n)), {
        headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${data.token}` },
        tags: { name: 'POST /referrals' },
    });
    check(res, { '201 Created': (r) => r.status === 201 });
}

// n번 슬롯의 의뢰 요청. 슬롯 ID·시간 규칙은 reset-slot.sql과 같다
function body(n) {
    const date = new Date(BASE_DATE + Math.floor(n / SLOTS_PER_DAY) * 86400000).toISOString().slice(0, 10);
    const startMin = 8 * 60 + (n % SLOTS_PER_DAY) * 30;
    return {
        referringInstitutionId: REQUESTER_INSTITUTION,
        examTypeId: EXAM_TYPE,
        patientName: `부하${n}`,
        idempotencyKey: uuid(),
        reservation: {
            slotId: `f1000000-0000-0000-0000-${n.toString(16).padStart(12, '0')}`,
            examInstitutionId: EXAM_INSTITUTION,
            equipmentId: EQUIPMENT,
            examDate: `${date}T00:00:00`,
            startTime: `${date}T${hhmm(startMin)}:00`,
            endTime: `${date}T${hhmm(startMin + 30)}:00`,
        },
    };
}

function hhmm(min) {
    return `${String(Math.floor(min / 60)).padStart(2, '0')}:${String(min % 60).padStart(2, '0')}`;
}

function uuid() {
    return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
        const r = (Math.random() * 16) | 0;
        return (c === 'x' ? r : (r & 0x3) | 0x8).toString(16);
    });
}
