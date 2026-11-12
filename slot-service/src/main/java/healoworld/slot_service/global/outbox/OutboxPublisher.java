package healoworld.slot_service.global.outbox;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "outbox.publish.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxPublisher {

    private static final long SEND_TIMEOUT_SECONDS = 15;   // Kafka 응답 대기 시간
    static final int BATCH_SIZE = 100;                      // 한 번에 읽는 최대 건수 (findTop100과 같게)
    // 한 회차에 보내는 최대 묶음 수. 스케줄러 스레드가 1개라 HOLD 만료 등 다른 스케줄 작업이
    // Outbox 회차가 끝날 때까지 기다리므로, 이벤트가 계속 쌓여도 회차가 끝없이 이어지지 않게 한다
    static final int MAX_BATCHES_PER_RUN = 10;

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final Clock clock;
    private final Tracer tracer;
    private final Propagator propagator;
    private final OutboxPublishMetrics outboxPublishMetrics;

    // 100건을 꽉 채워 읽었으면 쉬지 않고 바로 다음 묶음을 보낸다. 쌓인 이벤트가 회차마다 쉬는 시간만큼 밀리지 않는다
    @Scheduled(fixedDelayString = "${outbox.publish.fixed-delay-ms:1000}")   // 회차 종료 후 outbox.publish.fixed-delay-ms 뒤 시작
    public void publish() {
        for (int batch = 0; batch < MAX_BATCHES_PER_RUN; batch++) {
            if (!publishBatch()) {
                return;
            }
        }
    }

    // 묶음 하나를 생성 순서대로 보낸다. 꽉 찬 묶음을 모두 보냈으면 true (남은 이벤트가 더 있을 수 있다)
    // 덜 찼거나 발행이 실패하면 false로 회차를 끝낸다
    private boolean publishBatch() {
        List<OutboxEvent> events = outboxEventRepository.findTop100ByPublishedAtIsNullOrderByCreatedAtAsc();

        for (OutboxEvent event : events) {
            try {
                sendInOriginalTrace(event);
            } catch (Exception e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                outboxEventRepository.increaseRetryCount(event.getId(), LocalDateTime.now(clock));
                log.warn("Outbox 발행 실패, 회차 중단. id={}, type={}, topic={}, retry={}",
                        event.getId(), event.getEventType(), event.getTopic(),
                        event.getRetryCount() + 1, e);
                return false; // 뒤 이벤트가 먼저 나가지 않도록 이번 회차 중단
            }
            LocalDateTime publishedAt = LocalDateTime.now(clock);
            outboxEventRepository.markPublished(event.getId(), publishedAt);
            outboxPublishMetrics.recordWait(event, publishedAt);
            outboxPublishMetrics.recordDwell(event, publishedAt);
            log.debug("Outbox 발행 완료. id={}, type={}", event.getId(), event.getEventType());
        }
        return events.size() == BATCH_SIZE;
    }

    // 저장 시점의 trace를 부모로 span을 열어, 발행 메시지가 원래 요청과 같은 traceId를 갖게 한다
    private void sendInOriginalTrace(OutboxEvent event) throws Exception {
        if (event.getTraceParent() == null) {
            send(event);
            return;
        }

        Span span = propagator.extract(Map.of(OutboxTraceListener.TRACEPARENT, event.getTraceParent()), Map::get)
                .name("outbox publish")
                .start();
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            send(event);
        } catch (Exception e) {
            span.error(e);
            throw e;
        } finally {
            span.end();
        }
    }

    // 브로커 ack(acks=all)를 받을 때까지 대기
    private void send(OutboxEvent event) throws Exception {
        kafkaTemplate.send(toRecord(event)).get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private ProducerRecord<String, String> toRecord(OutboxEvent event) {
        ProducerRecord<String, String> record =
                new ProducerRecord<>(event.getTopic(), event.getMessageKey(), event.getPayload());
        record.headers().add("eventId", event.getId().toString().getBytes(StandardCharsets.UTF_8));
        record.headers().add("eventType", event.getEventType().getBytes(StandardCharsets.UTF_8));
        return record;
    }
}
