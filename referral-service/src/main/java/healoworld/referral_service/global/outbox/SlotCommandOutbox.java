package healoworld.referral_service.global.outbox;

import healoworld.referral_service.domain.entity.Reservation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class SlotCommandOutbox {
    private final OutboxEventRepository outboxEventRepository;
    private final JsonMapper jsonMapper;

    public UUID recordOutbox(Reservation reservation, String eventType) {
        UUID eventId = UUID.randomUUID();
        recordOutbox(reservation, eventId, eventType);

        return eventId;
    }

    public void recordOutbox(Reservation reservation, UUID eventId, String eventType) {
        String payloadJson = jsonMapper.writeValueAsString(ReservationEventPayload.from(reservation, eventId, eventType));

        OutboxEvent outboxEvent = OutboxEvent.create(eventId, "RESERVATION", reservation.getId(),
                "slot-commands", reservation.getSlotId().toString(), eventType, payloadJson);
        outboxEventRepository.save(outboxEvent);
        log.info("Outbox 기록. eventId={}, eventType={}, reservationId={}", outboxEvent.getId(), outboxEvent.getEventType(), outboxEvent.getAggregateId());
    }
}
