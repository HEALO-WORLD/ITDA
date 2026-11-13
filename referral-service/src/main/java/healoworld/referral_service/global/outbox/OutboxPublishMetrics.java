package healoworld.referral_service.global.outbox;

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

    // slot-service와 같은 이름·태그를 쓴다. application 태그로 두 서비스의 Outbox 대기를 같은 쿼리로 비교할 수 있다
    static final String WAIT = "outbox.publish.wait";

    private final MeterRegistry meterRegistry;

    // Outbox 대기: 행 저장(created_at) → 발행 완료(브로커 ack). 폴링 간격과 건별 ack 대기, 발행 재시도 시간이 여기에 드러난다
    // created_at은 엔티티 생성 시각이라 트랜잭션 커밋까지 남은 처리 시간도 일부 포함한다
    public void recordWait(OutboxEvent event, LocalDateTime publishedAt) {
        Duration wait = Duration.between(event.getCreatedAt(), publishedAt);
        if (wait.isNegative()) {
            log.debug("Outbox 대기 시간이 음수라 기록하지 않음(시계 차이). id={}, wait={}", event.getId(), wait);
            return;
        }
        Timer.builder(WAIT)
                .description("Outbox 행 저장부터 발행 완료까지 걸린 시간")
                .tag("type", event.getEventType())
                .publishPercentileHistogram()   // Grafana에서 p50/p95 계산용 bucket 노출
                .register(meterRegistry)
                .record(wait);
    }
}
