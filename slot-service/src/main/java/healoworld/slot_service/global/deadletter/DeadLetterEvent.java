package healoworld.slot_service.global.deadletter;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "p_dead_letter_events",
        uniqueConstraints = @UniqueConstraint(name = "uk_dead_letter_original",
                columnNames = {"original_topic", "original_partition", "original_offset"}),
        indexes = @Index(name = "idx_dead_letter_status_created", columnList = "dead_letter_status, created_at"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DeadLetterEvent {
    @Id
    private UUID id;

    @Column(nullable = false)
    private String originalTopic;

    @Column(nullable = false)
    private int originalPartition;

    @Column(nullable = false)
    private long originalOffset;

    private String messageKey;

    private String eventId;

    private String eventType;

    @Column(columnDefinition = "text")
    private String payload;

    private String exceptionClass;

    @Column(columnDefinition = "text")
    private String failureSummary;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DeadLetterStatus deadLetterStatus;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    private LocalDateTime resolvedAt;

    private UUID resolvedBy;

    public static DeadLetterEvent create(String originalTopic, int originalPartition, long originalOffset,
                                         String messageKey, String eventId, String eventType, String payload,
                                         String exceptionClass, String failureSummary
    ) {
        DeadLetterEvent e = new DeadLetterEvent();
        e.id = UUID.randomUUID();
        e.originalTopic = originalTopic;
        e.originalPartition = originalPartition;
        e.originalOffset = originalOffset;
        e.messageKey = messageKey;
        e.eventId = eventId;
        e.eventType = eventType;
        e.payload = payload;
        e.exceptionClass = exceptionClass;
        e.failureSummary = failureSummary;
        e.deadLetterStatus = DeadLetterStatus.PENDING;
        e.createdAt = LocalDateTime.now();

        return e;
    }

    public boolean isPending() {
        return deadLetterStatus == DeadLetterStatus.PENDING;
    }

    public void markIgnored(UUID adminId) {
        resolve(DeadLetterStatus.IGNORED, adminId);
    }

    public void markReprocessed(UUID adminId) {
        resolve(DeadLetterStatus.REPROCESSED, adminId);
    }

    private void resolve(DeadLetterStatus status, UUID adminId) {
        this.resolvedAt = LocalDateTime.now();
        this.resolvedBy = adminId;
        this.deadLetterStatus = status;
    }
}
