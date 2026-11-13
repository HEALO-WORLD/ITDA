package healoworld.referral_service.global.outbox;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

// slot-service OutboxPublisher(#257)와 같은 방식으로 발행한다
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxPublisher {
    private static final long SEND_TIMEOUT_SECONDS = 15;   // Kafka 응답 대기 시간
    static final int BATCH_SIZE = 100;                      // 한 번에 읽는 최대 건수 (findTop100과 같게)
    // 한 회차에 보내는 최대 묶음 수. 스케줄러 스레드가 1개라 정합성 점검·장애 후속 처리·재조정 만료 등 다른 스케줄 작업이
    // Outbox 회차가 끝날 때까지 기다리므로, 이벤트가 계속 쌓여도 회차가 끝없이 이어지지 않게 한다
    static final int MAX_BATCHES_PER_RUN = 10;

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
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
    //
    // 1) 묶음의 이벤트를 ack를 기다리지 않고 모두 보낸다 (건마다 ack를 기다리는 시간을 없앤다)
    // 2) ack를 보낸 순서대로 확인하며 발행 완료로 표시한다. 첫 실패에서 멈추고, 그 뒤 이벤트는
    //    이미 보냈더라도 표시하지 않는다. 다음 회차에 다시 나가고, 받는 쪽(slot)이 eventId로 중복을 거른다
    // 순서: producer가 enable.idempotence=true이고 max.in.flight.requests.per.connection이 5 이하(기본값 5)면
    //       같은 파티션(= 같은 slotId) 안의 순서가 지켜진다. 이 값을 5보다 크게 바꾸지 않는다
    private boolean publishBatch() {
        List<OutboxEvent> events = outboxEventRepository.findTop100ByPublishedAtIsNullOrderByCreatedAtAsc();

        List<CompletableFuture<?>> acks = new ArrayList<>(events.size());
        for (OutboxEvent event : events) {
            CompletableFuture<?> ack = sendInOriginalTrace(event);
            acks.add(ack);
            if (ack.isCompletedExceptionally()) {
                break;   // 보내는 순간 실패했으면(브로커 연결 불가 등) 뒤 이벤트는 보내지 않는다
            }
        }

        for (int i = 0; i < acks.size(); i++) {
            OutboxEvent event = events.get(i);
            try {
                acks.get(i).get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (Exception e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                outboxEventRepository.increaseRetryCount(event.getId(), LocalDateTime.now());
                log.warn("Outbox 발행 실패, 회차 중단. id={}, type={}, topic={}, retry={}",
                        event.getId(), event.getEventType(), event.getTopic(),
                        event.getRetryCount() + 1, e);
                return false; // 뒤 이벤트가 먼저 발행 완료로 표시되지 않도록 이번 회차 중단
            }
            LocalDateTime publishedAt = LocalDateTime.now();
            outboxEventRepository.markPublished(event.getId(), publishedAt);
            outboxPublishMetrics.recordWait(event, publishedAt);
            log.debug("Outbox 발행 완료. id={}, type={}", event.getId(), event.getEventType());
        }
        return events.size() == BATCH_SIZE;
    }

    // 저장 시점의 trace를 부모로 span을 열어, 발행 메시지가 원래 요청과 같은 traceId를 갖게 한다
    // span은 보내는 동안만 현재 범위로 두고, ack가 오면(성공·실패) 닫는다
    private CompletableFuture<?> sendInOriginalTrace(OutboxEvent event) {
        if (event.getTraceParent() == null) {
            return send(event);
        }

        Span span = propagator.extract(Map.of(OutboxTraceListener.TRACEPARENT, event.getTraceParent()), Map::get)
                .name("outbox publish")
                .start();
        CompletableFuture<?> ack;
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            ack = send(event);
        }
        return ack.whenComplete((result, e) -> {
            if (e != null) {
                span.error(e);
            }
            span.end();
        });
    }

    // ack(acks=all)를 기다리지 않고 보낸다. 보내는 순간 예외가 나도 실패한 future로 돌려 같은 경로로 처리한다
    private CompletableFuture<?> send(OutboxEvent event) {
        try {
            return kafkaTemplate.send(toRecord(event));
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    private ProducerRecord<String, String> toRecord(OutboxEvent event) {
        ProducerRecord<String, String> record =
                new ProducerRecord<>(event.getTopic(), event.getMessageKey(), event.getPayload());
        record.headers().add("eventId", event.getId().toString().getBytes(StandardCharsets.UTF_8));
        record.headers().add("eventType", event.getEventType().getBytes(StandardCharsets.UTF_8));
        return record;
    }
}
