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

        Timer.builder(DWELL)
                .description("받은 명령의 occurredAt부터 결과 이벤트 발행 완료까지 걸린 시간")
                .tag("type", event.getEventType())
                .publishPercentileHistogram()   // Grafana에서 p50/p95 계산용 bucket 노출
                .register(meterRegistry)
                .record(dwell);
    }
}
