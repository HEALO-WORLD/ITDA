package healoworld.slot_service.global.inbox;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Persistable;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "p_consumed_events")
@IdClass(ConsumedEventId.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ConsumedEvent implements Persistable<ConsumedEventId> {

    @Id
    private UUID id; // = eventId

    @Id
    @Column(nullable = false, length = 100)
    private String consumer;

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    @Column(name = "processed_at", nullable = false)
    private LocalDateTime processedAt;

    public static ConsumedEvent create(UUID eventId, String consumer, String eventType, LocalDateTime now) {
        ConsumedEvent e = new ConsumedEvent();
        e.id = eventId;
        e.consumer = consumer;
        e.eventType = eventType;
        e.processedAt = now;
        return e;
    }

    @Override
    public ConsumedEventId getId() {
        return new ConsumedEventId(id, consumer);
    }

    // 항상 INSERT로 처리해 중복 이벤트가 merge되지 않고 PK 충돌로 실패하게 한다
    @Override
    public boolean isNew() {
        return true;
    }
}
