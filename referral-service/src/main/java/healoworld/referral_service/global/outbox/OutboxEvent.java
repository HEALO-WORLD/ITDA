package healoworld.referral_service.global.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.Id;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
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

    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public static OutboxEvent create(UUID eventId, String aggregateType, UUID aggregateId,
                                     String topic, String messageKey, String eventType,
                                     String payloadJson) {
        OutboxEvent e = new OutboxEvent();
        e.id = eventId;
        e.aggregateType = aggregateType;
        e.aggregateId = aggregateId;
        e.topic = topic;
        e.messageKey = messageKey;
        e.eventType = eventType;
        e.payload = payloadJson;
        e.retryCount = 0;
        e.createdAt = e.updatedAt = LocalDateTime.now();
        return e;
    }
}
