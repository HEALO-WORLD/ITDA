package healoworld.referral_service.global.deadletter;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Entity
@Table(name = "p_dead_letter_events")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DeadLetterEvent {
    @Id
    private UUID id;

    private String originalTopic;
    private int originalPartition;
    private int originalOffset;
    private DeadLetterStatus deadLetterStatus;

    private UUID eventId;
    private String eventType;
    private UUID messageKey;
    private String payload;

}
