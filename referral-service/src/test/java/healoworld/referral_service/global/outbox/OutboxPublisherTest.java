package healoworld.referral_service.global.outbox;

import brave.Tracing;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.brave.bridge.BraveCurrentTraceContext;
import io.micrometer.tracing.brave.bridge.BravePropagator;
import io.micrometer.tracing.brave.bridge.BraveTracer;
import io.micrometer.tracing.brave.bridge.W3CPropagation;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Outbox 릴레이(OutboxPublisher) 단위 테스트.
 * <p>
 * - 미발행 이벤트를 저장 순서대로 보내고, 브로커 응답을 받은 것만 발행 완료로 기록한다
 * - 발행이 실패하면 재시도 횟수만 올리고 그 회차를 멈춰, 뒤 이벤트가 앞 이벤트보다 먼저 나가지 않게 한다
 *   (Kafka 중지 중 생성한 의뢰는 미발행으로 남았다가 재기동 후 순서대로 발행된다: SIT_012-2·3)
 * - 저장 시점의 traceparent가 있으면 같은 trace 안에서 발행한다
 * - Tracer는 실제 Brave 구현을 써서 trace 이어짐을 확인한다 (OutboxTraceListenerTest와 같은 구성)
 */
class OutboxPublisherTest {

    private OutboxEventRepository outboxEventRepository;
    private KafkaTemplate<String, String> kafkaTemplate;
    private Tracing tracing;
    private Tracer tracer;
    private SimpleMeterRegistry meterRegistry;
    private OutboxPublisher publisher;

    // send가 불릴 때의 현재 span (발행이 어느 trace 안에서 일어났는지)
    private final List<Span> spansAtSend = new ArrayList<>();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        outboxEventRepository = mock(OutboxEventRepository.class);
        kafkaTemplate = mock(KafkaTemplate.class);
        tracing = Tracing.newBuilder().traceId128Bit(true).propagationFactory(new W3CPropagation()).build();
        tracer = new BraveTracer(tracing.tracer(), new BraveCurrentTraceContext(tracing.currentTraceContext()));
        meterRegistry = new SimpleMeterRegistry();
        publisher = new OutboxPublisher(outboxEventRepository, kafkaTemplate, tracer, new BravePropagator(tracing),
                new OutboxPublishMetrics(meterRegistry));
    }

    @AfterEach
    void tearDown() {
        tracing.close();
    }

    @Test
    @DisplayName("미발행 이벤트가 없으면 아무것도 보내지 않는다")
    void noPendingEvents() {
        given(outboxEventRepository.findTop100ByPublishedAtIsNullOrderByCreatedAtAsc()).willReturn(List.of());

        publisher.publish();

        verify(kafkaTemplate, never()).send(any(ProducerRecord.class));
        verify(outboxEventRepository, never()).markPublished(any(), any());
    }

    @Test
    @DisplayName("미발행 이벤트를 조회 순서대로 보내고, 하나씩 브로커 응답을 받은 뒤 발행 완료로 기록한다")
    void publishesInOrderAndMarksEach() {
        OutboxEvent first = event("HoldRequested");
        OutboxEvent second = event("BookSlot");
        given(outboxEventRepository.findTop100ByPublishedAtIsNullOrderByCreatedAtAsc()).willReturn(List.of(first, second));
        brokerAccepts();

        publisher.publish();

        InOrder inOrder = inOrder(kafkaTemplate, outboxEventRepository);
        inOrder.verify(kafkaTemplate).send(recordOf(first));
        inOrder.verify(outboxEventRepository).markPublished(eq(first.getId()), any());
        inOrder.verify(kafkaTemplate).send(recordOf(second));
        inOrder.verify(outboxEventRepository).markPublished(eq(second.getId()), any());
        verify(outboxEventRepository, never()).increaseRetryCount(any(), any());
    }

    @Test
    @DisplayName("메시지는 Outbox의 토픽·key·payload 그대로이고, eventId·eventType 헤더를 담는다")
    void recordCarriesTopicKeyPayloadAndHeaders() {
        OutboxEvent event = event("HoldRequested");
        given(outboxEventRepository.findTop100ByPublishedAtIsNullOrderByCreatedAtAsc()).willReturn(List.of(event));
        brokerAccepts();

        publisher.publish();

        ProducerRecord<String, String> record = sentRecords().getFirst();
        assertThat(record.topic()).isEqualTo("slot-commands");
        assertThat(record.key()).isEqualTo(event.getMessageKey());
        assertThat(record.value()).isEqualTo(event.getPayload());
        assertThat(header(record, "eventId")).isEqualTo(event.getId().toString());
        assertThat(header(record, "eventType")).isEqualTo("HoldRequested");
    }

    @Test
    @DisplayName("발행이 실패하면 재시도 횟수만 올리고 발행 완료로 기록하지 않으며, 뒤 이벤트는 이번 회차에 보내지 않는다")
    void failureStopsRoundAndIncreasesRetryCount() {
        OutboxEvent first = event("HoldRequested");
        OutboxEvent second = event("BookSlot");
        given(outboxEventRepository.findTop100ByPublishedAtIsNullOrderByCreatedAtAsc()).willReturn(List.of(first, second));
        given(kafkaTemplate.send(any(ProducerRecord.class)))
                .willReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")));

        publisher.publish();

        verify(kafkaTemplate, times(1)).send(any(ProducerRecord.class));
        verify(kafkaTemplate, never()).send(recordOf(second));
        verify(outboxEventRepository).increaseRetryCount(eq(first.getId()), any());
        verify(outboxEventRepository, never()).markPublished(any(), any());
    }

    @Test
    @DisplayName("send 호출 자체가 예외를 던져도(브로커 연결 실패 등) 같은 방식으로 회차를 멈추고 재시도 횟수를 올린다")
    void sendThrowingStopsRound() {
        OutboxEvent first = event("HoldRequested");
        OutboxEvent second = event("BookSlot");
        given(outboxEventRepository.findTop100ByPublishedAtIsNullOrderByCreatedAtAsc()).willReturn(List.of(first, second));
        given(kafkaTemplate.send(any(ProducerRecord.class))).willThrow(new IllegalStateException("metadata timeout"));

        publisher.publish();

        verify(outboxEventRepository).increaseRetryCount(eq(first.getId()), any());
        verify(outboxEventRepository, never()).markPublished(any(), any());
        verify(kafkaTemplate, never()).send(recordOf(second));
    }

    @Test
    @DisplayName("앞 이벤트가 성공하고 다음 이벤트가 실패하면 앞 이벤트만 발행 완료이고 실패한 이벤트부터 다음 회차에 다시 보낸다")
    void partialSuccessKeepsFailedForNextRound() {
        OutboxEvent first = event("HoldRequested");
        OutboxEvent second = event("BookSlot");
        OutboxEvent third = event("ReleaseSlot");
        given(outboxEventRepository.findTop100ByPublishedAtIsNullOrderByCreatedAtAsc())
                .willReturn(List.of(first, second, third))
                .willReturn(List.of(second, third));   // 다음 회차: 발행 완료된 첫 이벤트는 조회되지 않는다
        CompletableFuture<SendResult<String, String>> ok = CompletableFuture.completedFuture(null);
        given(kafkaTemplate.send(any(ProducerRecord.class)))
                .willReturn(ok)
                .willReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")))
                .willReturn(ok);

        publisher.publish();

        verify(outboxEventRepository).markPublished(eq(first.getId()), any());
        verify(outboxEventRepository).increaseRetryCount(eq(second.getId()), any());
        verify(kafkaTemplate, never()).send(recordOf(third));

        publisher.publish();   // 브로커 복구 후 다음 회차

        verify(outboxEventRepository).markPublished(eq(second.getId()), any());
        verify(outboxEventRepository).markPublished(eq(third.getId()), any());
        InOrder inOrder = inOrder(kafkaTemplate);
        inOrder.verify(kafkaTemplate).send(recordOf(first));
        inOrder.verify(kafkaTemplate, times(2)).send(recordOf(second));
        inOrder.verify(kafkaTemplate).send(recordOf(third));
    }

    @Test
    @DisplayName("저장 시점의 traceparent가 있으면 그 trace의 자식 span 안에서 발행해 같은 traceId를 잇는다")
    void publishesInsideOriginalTrace() {
        Span request = tracer.nextSpan().name("요청").start();
        request.end();
        OutboxEvent event = event("HoldRequested");
        event.assignTraceParent("00-" + request.context().traceId() + "-" + request.context().spanId() + "-01");
        given(outboxEventRepository.findTop100ByPublishedAtIsNullOrderByCreatedAtAsc()).willReturn(List.of(event));
        brokerAccepts();

        publisher.publish();

        assertThat(spansAtSend).singleElement().satisfies(span -> {
            assertThat(span).isNotNull();
            assertThat(span.context().traceId()).isEqualTo(request.context().traceId());
            assertThat(span.context().parentId()).isEqualTo(request.context().spanId());
        });
        // 발행이 끝나면 span을 닫아 현재 trace가 남지 않는다
        assertThat(tracer.currentSpan()).isNull();
    }

    @Test
    @DisplayName("traceparent가 없으면 trace 없이 그대로 발행한다")
    void publishesWithoutTrace() {
        OutboxEvent event = event("HoldRequested");
        given(outboxEventRepository.findTop100ByPublishedAtIsNullOrderByCreatedAtAsc()).willReturn(List.of(event));
        brokerAccepts();

        publisher.publish();

        assertThat(spansAtSend).containsExactly((Span) null);
        verify(outboxEventRepository).markPublished(eq(event.getId()), any());
    }

    // ---------- 발행 대기 메트릭 ----------

    @Test
    @DisplayName("발행에 성공하면 이벤트 종류별로 Outbox 대기 시간을 한 번 기록한다")
    void recordsWaitOnSuccess() {
        OutboxEvent event = event("BookSlot");
        given(outboxEventRepository.findTop100ByPublishedAtIsNullOrderByCreatedAtAsc()).willReturn(List.of(event));
        brokerAccepts();

        publisher.publish();

        Timer timer = meterRegistry.find(OutboxPublishMetrics.WAIT).tag("type", "BookSlot").timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("발행에 실패하면 Outbox 대기 시간을 기록하지 않는다")
    void doesNotRecordWaitOnFailure() {
        OutboxEvent event = event("BookSlot");
        given(outboxEventRepository.findTop100ByPublishedAtIsNullOrderByCreatedAtAsc()).willReturn(List.of(event));
        given(kafkaTemplate.send(any(ProducerRecord.class)))
                .willReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")));

        publisher.publish();

        assertThat(meterRegistry.find(OutboxPublishMetrics.WAIT).timer()).isNull();
    }

    @Test
    @DisplayName("Outbox 대기 시간은 행 저장(created_at)부터 발행 완료까지이고, 음수면(시계 차이) 기록하지 않는다")
    void waitIsCreatedAtToPublishedAt() {
        OutboxPublishMetrics metrics = new OutboxPublishMetrics(meterRegistry);
        OutboxEvent event = event("HoldRequested");
        LocalDateTime createdAt = event.getCreatedAt();

        metrics.recordWait(event, createdAt.plusNanos(300_000_000));
        metrics.recordWait(event, createdAt.minusSeconds(1));

        Timer timer = meterRegistry.find(OutboxPublishMetrics.WAIT).tag("type", "HoldRequested").timer();
        assertThat(timer.count()).isEqualTo(1);
        assertThat(timer.totalTime(TimeUnit.MILLISECONDS)).isEqualTo(300);
    }

    // ---------- 도우미 ----------

    // 브로커가 모든 메시지를 받는다. send 시점의 현재 span을 기록한다
    private void brokerAccepts() {
        given(kafkaTemplate.send(any(ProducerRecord.class))).willAnswer(inv -> {
            spansAtSend.add(tracer.currentSpan());
            return CompletableFuture.completedFuture(null);
        });
    }

    @SuppressWarnings("unchecked")
    private List<ProducerRecord<String, String>> sentRecords() {
        ArgumentCaptor<ProducerRecord<String, String>> captor = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate, org.mockito.Mockito.atLeastOnce()).send(captor.capture());
        return captor.getAllValues();
    }

    // 이 Outbox 이벤트로 만든 메시지인지 (eventId 헤더로 구분)
    private static ProducerRecord<String, String> recordOf(OutboxEvent event) {
        return org.mockito.ArgumentMatchers.argThat(record -> record != null
                && event.getId().toString().equals(header(record, "eventId")));
    }

    private static String header(ProducerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    private static OutboxEvent event(String eventType) {
        UUID slotId = UUID.randomUUID();
        return OutboxEvent.create(UUID.randomUUID(), "RESERVATION", UUID.randomUUID(), "slot-commands",
                slotId.toString(), eventType, "{\"slotId\":\"" + slotId + "\"}");
    }
}
