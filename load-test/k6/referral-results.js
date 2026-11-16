// referral-service 단독 Outbox 부하 테스트 (slot-service 없이)
// slot-results에 SlotHeld를 예약의 slotId 키로 직접 넣는다
// referral은 SlotHeld 1건마다 예약을 REQUESTED → HELD로 바꾸고 Outbox에 BookSlot 1건을 쌓는다
// 목적: referral Outbox 발행 처리량만 따로 잰다. slot의 처리·대기가 섞이지 않는다 (slot 단독 테스트 slot-commands.js의 짝)
//
// 규모는 #257 slot 단독 테스트와 Outbox 이벤트량을 맞춘다
//   slot은 예약 1건에 Outbox 2건(SlotHeld·SlotBooked)이고, 여기서는 SlotHeld 1건에 Outbox 1건(BookSlot)이다
//   그래서 초당 건수를 두 배로 잡는다: fixed 20 → 40, ramp 10,30,50,70,90 → 20,60,100,140,180
//
// 필요한 것: xk6-kafka가 들어간 k6 (load-test/k6/Dockerfile.kafka로 이미지 빌드)
//
// 실행 전 (측정할 때마다)
//   1) slot-service는 띄우지 않는다. 띄우면 BookSlot을 받아 처리하고 그 결과가 다시 referral로 돌아와 측정이 섞인다
//   2) reset-referral-outbox.sql로 의뢰·예약 초기화. 부하가 크면 예약 수를 늘린다:  psql ... -v reservations=40000 < reset-referral-outbox.sql
//   반복 측정은 load-test/run-referral-outbox.sh가 이 순서를 대신한다
//
// 실행 (로컬 Git Bash, 프로젝트 루트)
//   MSYS_NO_PATHCONV=1 docker run --rm -i --network itda_default -v "$PWD/load-test:/load-test" \
//     itda-k6-kafka run -e BROKER=kafka:29092 <옵션> /load-test/k6/referral-results.js
//   고정 속도 옵션:   -e RATE=40 -e DURATION=1m
//   단계 증가 옵션:   -e MODE=ramp -e LEVELS=20,60,100,140,180 -e STEP=1m -e RESERVATIONS=40000
//   RATE·LEVELS는 초당 SlotHeld 수다. referral Outbox 이벤트(BookSlot)도 같은 수만큼 생긴다
//
// 브로커 (BROKER)
//   로컬 Docker Desktop:         --network itda_default -e BROKER=kafka:29092
//   EC2 (--network host):        localhost:9092 (기본값)
//
// slot-service를 다시 켜기 전에, 쌓인 가짜 BookSlot을 건너뛰게 한다 (슬롯이 없는 예약이라 실패 결과가 referral로 돌아온다)
//   MSYS_NO_PATHCONV=1 docker compose exec kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server kafka:29092 \
//     --group slot-service --topic slot-commands --reset-offsets --to-latest --execute
import { Writer, SchemaRegistry, SCHEMA_TYPE_STRING, BALANCER_MURMUR2 } from 'k6/x/kafka';
import { fail } from 'k6';
import exec from 'k6/execution';
import { Counter } from 'k6/metrics';

const BROKER = __ENV.BROKER || 'localhost:9092';
const TOPIC = 'slot-results';
const MODE = __ENV.MODE || 'fixed';             // fixed: 고정 속도, ramp: 단계적 증가
const RATE = Number(__ENV.RATE || 40);          // fixed: 초당 SlotHeld 수
const DURATION = __ENV.DURATION || '2m';        // fixed: 본 측정 시간
const LEVELS = (__ENV.LEVELS || '20,60,100,140,180').split(',').map(Number);   // ramp: 단계별 초당 SlotHeld 수
const STEP = __ENV.STEP || '1m';                // ramp: 단계마다 유지 시간
const WARMUP_RATE = Number(__ENV.WARMUP_RATE || 5);

// load-test/sql/reset-referral-outbox.sql과 맞춘 값
const TOTAL_RESERVATIONS = Number(__ENV.RESERVATIONS || 10000);   // reset-referral-outbox.sql의 -v reservations 값과 같게
const MAIN_OFFSET = 1000;                       // 워밍업은 0~999번, 본 측정은 1000번 예약부터

// slot이 실제로 보내는 결과와 같은 키(slotId)·같은 파티셔너를 쓴다
// 정의되지 않으면 기본값(라운드 로빈)이 되어 실제와 다른 파티션으로 가므로 바로 멈춘다
if (!BALANCER_MURMUR2) {
    throw new Error('xk6-kafka에 BALANCER_MURMUR2가 없다. 버전을 확인한다');
}

// VU마다 Writer를 하나씩 만든다
// batchSize 1: 메시지를 모으지 않고 바로 보낸다. 배치 대기 시간이 처리 시각에 섞이지 않게 한다
const writer = new Writer({
    brokers: [BROKER],
    topic: TOPIC,
    balancer: BALANCER_MURMUR2,                 // Java 기본 파티셔너와 같은 해시
    batchSize: 1,
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
    if (n >= TOTAL_RESERVATIONS || (__ENV.OFFSET === '0' && n >= MAIN_OFFSET)) {
        fail(`예약 부족: ${n}번. RATE·DURATION을 줄이거나 reset-referral-outbox.sql의 -v reservations와 -e RESERVATIONS를 늘린다`);
    }

    const hex = n.toString(16).padStart(12, '0');
    const slotId = `f1000000-0000-0000-0000-${hex}`;
    const reservationId = `f2000000-0000-0000-0000-${hex}`;
    const eventId = `f4000000-0000-0000-0000-${hex}`;   // 회차마다 reset이 처리 기록을 지우므로 같은 ID를 다시 써도 된다

    try {
        writer.produce({ messages: [slotHeld(eventId, slotId, reservationId)] });
    } catch (e) {
        produceFailed.add(1);
        console.error(`발행 실패 reservation=${reservationId}: ${e}`);
    }
}

export function teardown() {
    writer.close();
}

// referral SlotResultMessage와 같은 필드. 헤더 eventId·eventType으로 이벤트를 구분한다
function slotHeld(eventId, slotId, reservationId) {
    const payload = {
        eventId,
        eventType: 'SlotHeld',
        occurredAt: nowKst(),
        slotId,
        reservationId,
    };
    return {
        key: schemaRegistry.serialize({ data: slotId, schemaType: SCHEMA_TYPE_STRING }),
        value: schemaRegistry.serialize({ data: JSON.stringify(payload), schemaType: SCHEMA_TYPE_STRING }),
        headers: { eventId, eventType: 'SlotHeld' },
    };
}

// LocalDateTime(시간대 없음) 형식의 한국 시각
function nowKst() {
    return new Date(Date.now() + 9 * 3600 * 1000).toISOString().slice(0, 23);
}
