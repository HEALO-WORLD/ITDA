package healoworld.referral_service.global.outbox;

import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import jakarta.persistence.PrePersist;
import org.springframework.beans.factory.ObjectProvider;

import java.util.HashMap;
import java.util.Map;

/**
 * Outbox 이벤트를 저장하는 시점의 trace 정보(W3C traceparent)를 기록한다.
 * 발행은 스케줄러 스레드에서 일어나므로, 저장 시점에 남겨 둬야 원래 요청과 같은 traceId로 이어서 발행할 수 있다.
 * Hibernate가 Spring 빈 컨테이너로 생성하므로 생성자 주입을 받는다.
 */
public class OutboxTraceListener {

    static final String TRACEPARENT = "traceparent";

    private final ObjectProvider<Tracer> tracerProvider;
    private final ObjectProvider<Propagator> propagatorProvider;

    public OutboxTraceListener(ObjectProvider<Tracer> tracerProvider,
                               ObjectProvider<Propagator> propagatorProvider) {
        this.tracerProvider = tracerProvider;
        this.propagatorProvider = propagatorProvider;
    }

    @PrePersist
    void captureTraceParent(OutboxEvent event) {
        Tracer tracer = tracerProvider.getIfAvailable();
        Propagator propagator = propagatorProvider.getIfAvailable();
        if (tracer == null || propagator == null) {
            return;
        }

        // 진행 중인 trace가 없으면(스케줄러 등) 기록하지 않는다
        TraceContext context = tracer.currentTraceContext().context();
        if (context == null) {
            return;
        }

        Map<String, String> carrier = new HashMap<>();
        propagator.inject(context, carrier, Map::put);
        event.assignTraceParent(carrier.get(TRACEPARENT));
    }
}
