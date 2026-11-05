package healoworld.slot_service.global.outbox;

import brave.Tracing;
import healoworld.slot_service.support.FixedClockConfig;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.brave.bridge.BraveCurrentTraceContext;
import io.micrometer.tracing.brave.bridge.BravePropagator;
import io.micrometer.tracing.brave.bridge.BraveTracer;
import io.micrometer.tracing.brave.bridge.W3CPropagation;
import io.micrometer.tracing.propagation.Propagator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class OutboxTraceListenerTest {

    private Tracing tracing;
    private Tracer tracer;
    private OutboxTraceListener listener;

    @BeforeEach
    void setUp() {
        tracing = Tracing.newBuilder().traceId128Bit(true).propagationFactory(new W3CPropagation()).build();
        tracer = new BraveTracer(tracing.tracer(), new BraveCurrentTraceContext(tracing.currentTraceContext()));

        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("tracer", tracer);
        beanFactory.addBean("propagator", new BravePropagator(tracing));
        listener = new OutboxTraceListener(beanFactory.getBeanProvider(Tracer.class),
                beanFactory.getBeanProvider(Propagator.class));
    }

    @AfterEach
    void tearDown() {
        tracing.close();
    }

    @Test
    void 진행_중인_trace가_있으면_W3C_traceparent를_기록한다() {
        OutboxEvent event = outboxEvent();
        Span span = tracer.nextSpan().name("요청").start();

        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            listener.captureTraceParent(event);
        } finally {
            span.end();
        }

        assertThat(event.getTraceParent())
                .isEqualTo("00-" + span.context().traceId() + "-" + span.context().spanId() + "-01");
    }

    @Test
    void 진행_중인_trace가_없으면_기록하지_않는다() {
        OutboxEvent event = outboxEvent();

        listener.captureTraceParent(event);

        assertThat(event.getTraceParent()).isNull();
    }

    @Test
    void Tracer_빈이_없으면_기록하지_않는다() {
        StaticListableBeanFactory empty = new StaticListableBeanFactory();
        OutboxTraceListener noTracing = new OutboxTraceListener(empty.getBeanProvider(Tracer.class),
                empty.getBeanProvider(Propagator.class));
        OutboxEvent event = outboxEvent();

        noTracing.captureTraceParent(event);

        assertThat(event.getTraceParent()).isNull();
    }

    private OutboxEvent outboxEvent() {
        UUID slotId = UUID.randomUUID();
        return OutboxEvent.create(UUID.randomUUID(), "SLOT", slotId, "slot-results",
                slotId.toString(), "SlotHeld", "{}", FixedClockConfig.NOW);
    }
}
