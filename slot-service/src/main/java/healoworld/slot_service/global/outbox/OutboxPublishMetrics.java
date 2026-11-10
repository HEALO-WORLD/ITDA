package healoworld.slot_service.global.outbox;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;

// Outbox 발행 시점에 기록하는 메트릭
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxPublishMetrics {

    static final String DWELL = "slot.event.dwell";
    static final String WAIT = "outbox.publish.wait";   // referral도 같은 이름을 쓰면 application 태그로 비교할 수 있다

    private final MeterRegistry meterRegistry;

    // 체류 시간: 받은 명령의 occurredAt → 결과 이벤트 발행 완료(브로커 ack)
    // Referral Outbox 대기 + Kafka 전달 + Slot 처리 + Slot Outbox 대기를 모두 포함한다
    public void recordDwell(OutboxEvent event, LocalDateTime publishedAt) {
        if (event.getSourceOccurredAt() == null) {
            return;   // 원인 명령이 없는 이벤트(HoldExpired, EquipmentDowntimeStarted)는 제외
        }

        Duration dwell = Duration.between(event.getSourceOccurredAt(), publishedAt);
        if (dwell.isNegative()) {
            log.debug("체류 시간이 음수라 기록하지 않음(시계 차이). id={}, dwell={}", event.getId(), dwell);
            return;
        }

        timer(DWELL, "받은 명령의 occurredAt부터 결과 이벤트 발행 완료까지 걸린 시간", event).record(dwell);
    }

    // Outbox 대기: 행 저장(created_at) → 발행 완료(브로커 ack). 폴링 간격과 발행 재시도 시간이 여기에 드러난다
    // created_at은 핸들러 트랜잭션 시작 시각이라 커밋까지 남은 처리 시간도 일부 포함한다
    public void recordWait(OutboxEvent event, LocalDateTime publishedAt) {
        Duration wait = Duration.between(event.getCreatedAt(), publishedAt);
        if (wait.isNegative()) {
            log.debug("Outbox 대기 시간이 음수라 기록하지 않음(시계 차이). id={}, wait={}", event.getId(), wait);
            return;
        }
        timer(WAIT, "Outbox 행 저장부터 발행 완료까지 걸린 시간", event).record(wait);
    }

    private Timer timer(String name, String description, OutboxEvent event) {
        return Timer.builder(name)
                .description(description)
                .tag("type", event.getEventType())
                .publishPercentileHistogram()   // Grafana에서 p50/p95 계산용 bucket 노출
                .register(meterRegistry);
    }
}
