package healoworld.slot_service.global.outbox;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@EntityListeners(OutboxTraceListener.class)
@Table(name = "p_outbox_events",
        indexes = @Index(name = "idx_outbox_unpublished", columnList = "published_at, created_at"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OutboxEvent {

    @Id
    private UUID id; // = eventId

    @Column(name = "aggregate_type", nullable = false, length = 50)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false)
    private UUID aggregateId;

    @Column(nullable = false, length = 100)
    private String topic;

    @Column(name = "message_key", nullable = false, length = 100)
    private String messageKey;

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "json")
    private String payload;

    @Column(name = "retry_count", nullable = false)
    private int retryCount;

    // 저장 시점의 W3C traceparent. 발행할 때 이 trace를 부모로 이어 traceId를 유지한다 (trace가 없으면 NULL)
    @Column(name = "trace_parent", length = 55)
    private String traceParent;

    // 이 결과를 만든 명령의 occurredAt. 발행 완료 시 체류 시간(slot.event.dwell)을 잰다 (원인 명령이 없으면 NULL)
    @Column(name = "source_occurred_at")
    private LocalDateTime sourceOccurredAt;

    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public static OutboxEvent create(UUID eventId, String aggregateType, UUID aggregateId,
                                     String topic, String messageKey, String eventType,
                                     String payloadJson, LocalDateTime now) {
        return create(eventId, aggregateType, aggregateId, topic, messageKey, eventType, payloadJson, null, now);
    }

    // 받은 명령에 대한 결과 이벤트용. sourceOccurredAt은 원인 명령의 occurredAt
    public static OutboxEvent create(UUID eventId, String aggregateType, UUID aggregateId,
                                     String topic, String messageKey, String eventType,
                                     String payloadJson, LocalDateTime sourceOccurredAt, LocalDateTime now) {
        OutboxEvent e = new OutboxEvent();
        e.id = eventId;
        e.aggregateType = aggregateType;
        e.aggregateId = aggregateId;
        e.topic = topic;
        e.messageKey = messageKey;
        e.eventType = eventType;
        e.payload = payloadJson;
        e.sourceOccurredAt = sourceOccurredAt;
        e.retryCount = 0;
        e.createdAt = e.updatedAt = now;
        return e;
    }

    void assignTraceParent(String traceParent) {
        this.traceParent = traceParent;
    }
}
