// slot-service 단독 부하 테스트 (referral-service 없이)
// slot-commands에 HoldRequested → BookSlot을 같은 slotId 키로 직접 넣는다
// 예약 1건마다 slot은 SlotHeld, SlotBooked 두 건을 Outbox에 쌓으므로 실제와 같은 발행량이 만들어진다
// 목적: slot Outbox 발행 처리량(troubleshooting.md 2번)만 따로 잰다. referral의 대기가 섞이지 않는다
//
// 실제 흐름과 다른 점: 실제로는 referral이 SlotHeld를 받은 뒤 BookSlot을 보낸다.
// 여기서는 두 명령을 동시에 넣으므로 BookSlot이 SlotHeld 발행을 기다리지 않는다. Outbox에 쌓이는 양은 같다
//
// 필요한 것: xk6-kafka가 들어간 k6 (load-test/k6/Dockerfile.kafka로 이미지 빌드)
// 전체 순서: docs/work/1112-slot-Outbox-개선과-단독-부하-테스트-순서.md
//
// 실행 전 (측정할 때마다)
//   1) referral-service는 띄우지 않는다 (로컬: slot-service만 띄움, EC2: dc stop referral-service)
//   2) reset-slot.sql로 슬롯 초기화. 부하가 크면 슬롯 수를 늘린다:  psql ... -v slots=30000 < reset-slot.sql
//
// 실행 (로컬 Git Bash, 프로젝트 루트)
//   MSYS_NO_PATHCONV=1 docker run --rm -i --network itda_default -v "$PWD/load-test:/load-test" \
//     itda-k6-kafka run -e BROKER=kafka:29092 <옵션> /load-test/k6/slot-commands.js
//   고정 속도 옵션:   -e RATE=20 -e DURATION=1m
//   단계 증가 옵션:   -e MODE=ramp -e LEVELS=10,30,50,70,90 -e STEP=1m -e SLOTS=30000
//   RATE·LEVELS는 초당 "예약" 수다. Kafka 메시지는 그 2배, slot Outbox 이벤트도 그 2배다
//
// 브로커 (BROKER)
//   로컬 Docker Desktop:         --network itda_default -e BROKER=kafka:29092
//   EC2 (--network host):        localhost:9092 (기본값)
//
// referral-service를 다시 켜기 전에, 쌓인 가짜 결과 이벤트를 건너뛰게 한다
//   MSYS_NO_PATHCONV=1 docker compose exec kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server kafka:29092 \
//     --group referral-service --topic slot-results --reset-offsets --to-latest --execute
import { Writer, SchemaRegistry, SCHEMA_TYPE_STRING, BALANCER_MURMUR2 } from 'k6/x/kafka';
import { fail } from 'k6';
import exec from 'k6/execution';
import { Counter } from 'k6/metrics';

const BROKER = __ENV.BROKER || 'localhost:9092';
const TOPIC = 'slot-commands';
const MODE = __ENV.MODE || 'fixed';             // fixed: 고정 속도, ramp: 단계적 증가
const RATE = Number(__ENV.RATE || 20);          // fixed: 초당 예약 수
const DURATION = __ENV.DURATION || '2m';        // fixed: 본 측정 시간
const LEVELS = (__ENV.LEVELS || '10,30,50,70,90').split(',').map(Number);   // ramp: 단계별 초당 예약 수
const STEP = __ENV.STEP || '1m';                // ramp: 단계마다 유지 시간
const WARMUP_RATE = Number(__ENV.WARMUP_RATE || 5);

// load-test/sql/reset-slot.sql과 맞춘 값
const TOTAL_SLOTS = Number(__ENV.SLOTS || 10000);   // reset-slot.sql의 -v slots 값과 같게
const MAIN_OFFSET = 1000;                       // 워밍업은 0~999번, 본 측정은 1000번 슬롯부터

// 같은 키는 같은 파티션으로 가야 HoldRequested → BookSlot 순서가 지켜진다
// 정의되지 않으면 기본값(라운드 로빈)이 되어 순서가 깨지므로 바로 멈춘다
if (!BALANCER_MURMUR2) {
    throw new Error('xk6-kafka에 BALANCER_MURMUR2가 없다. 버전을 확인한다');
}

// VU마다 Writer를 하나씩 만든다
// batchSize 2: 한 번에 보내는 두 메시지(같은 파티션)가 모이면 바로 보낸다. 배치 대기 시간이 occurredAt 이후 지연에 섞이지 않게 한다
const writer = new Writer({
    brokers: [BROKER],
    topic: TOPIC,
    balancer: BALANCER_MURMUR2,                 // Java 기본 파티셔너와 같은 해시
    batchSize: 2,
    requiredAcks: 1,                            // 기본값 0은 브로커 확인 없이 보내 유실돼도 produce_failed에 안 잡힌다
    autoCreateTopic: false,
});
const schemaRegistry = new SchemaRegistry();

const produceFailed = new Counter('produce_failed');

const peak = MODE === 'ramp' ? Math.max(...LEVELS) : RATE;
const vus = Math.max(20, peak * 2);

const main = MODE === 'ramp'
    ? {
        executor: 'ramping-arrival-rate',
        startRate: LEVELS[0], timeUnit: '1s',
        stages: LEVELS.flatMap((level) => [
            { target: level, duration: '10s' },     // 다음 단계로 올리는 구간
            { target: level, duration: STEP },      // 유지 구간
        ]),
    }
    : {
        executor: 'constant-arrival-rate',
        rate: RATE, timeUnit: '1s', duration: DURATION,
    };

export const options = {
    scenarios: {
        warmup: {
            executor: 'constant-arrival-rate',
            rate: WARMUP_RATE, timeUnit: '1s', duration: '20s',
            preAllocatedVUs: 10, maxVUs: 50,
            tags: { phase: 'warmup' },
            env: { OFFSET: '0' },
        },
        main: {
            ...main,
            startTime: '30s',                       // 워밍업 이벤트가 모두 발행될 시간을 둔다
            preAllocatedVUs: vus, maxVUs: vus * 5,
            tags: { phase: 'main' },
            env: { OFFSET: String(MAIN_OFFSET) },
        },
    },
    thresholds: {
        'produce_failed': ['count==0'],
    },
    summaryTrendStats: ['avg', 'p(50)', 'p(95)', 'p(99)', 'max'],
};

export default function () {
    const n = Number(__ENV.OFFSET) + exec.scenario.iterationInTest;
    if (n >= TOTAL_SLOTS || (__ENV.OFFSET === '0' && n >= MAIN_OFFSET)) {
        fail(`슬롯 부족: ${n}번. RATE·DURATION을 줄이거나 reset-slot.sql의 -v slots와 -e SLOTS를 늘린다`);
    }

    const slotId = `f1000000-0000-0000-0000-${n.toString(16).padStart(12, '0')}`;
    const reservationId = uuid();               // slot은 referral에 확인하지 않으므로 아무 UUID나 된다

    try {
        writer.produce({
            messages: [
                message(slotId, reservationId, 'HoldRequested'),
                message(slotId, reservationId, 'BookSlot'),
            ],
        });
    } catch (e) {
        produceFailed.add(1);
        console.error(`발행 실패 slot=${slotId}: ${e}`);
    }
}

export function teardown() {
    writer.close();
}

// slot의 HoldRequestedEvent·BookSlotEvent와 같은 필드. 헤더 eventType으로 이벤트를 구분한다
function message(slotId, reservationId, eventType) {
    const eventId = uuid();
    const payload = {
        eventId,
        eventType,
        occurredAt: nowKst(),
        slotId,
        reservationId,
    };
    return {
        key: schemaRegistry.serialize({ data: slotId, schemaType: SCHEMA_TYPE_STRING }),
        value: schemaRegistry.serialize({ data: JSON.stringify(payload), schemaType: SCHEMA_TYPE_STRING }),
        headers: { eventId, eventType },
    };
}

// LocalDateTime(시간대 없음) 형식의 한국 시각. slot은 이 값으로 체류 시간(slot.event.dwell)을 잰다
function nowKst() {
    return new Date(Date.now() + 9 * 3600 * 1000).toISOString().slice(0, 23);
}

function uuid() {
    return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
        const r = (Math.random() * 16) | 0;
        return (c === 'x' ? r : (r & 0x3) | 0x8).toString(16);
    });
}
