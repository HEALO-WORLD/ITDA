package healoworld.referral_service.global.outbox;

import healoworld.referral_service.domain.entity.Reservation;
import healoworld.referral_service.domain.enums.ReferralStatus;
import healoworld.referral_service.domain.enums.ReservationStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

import static healoworld.referral_service.support.DomainFixtures.referralIn;
import static healoworld.referral_service.support.DomainFixtures.reservationIn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class SlotCommandOutboxTest {

    @Mock
    private OutboxEventRepository outboxEventRepository;

    // payload 내용까지 확인하려고 실제 직렬화를 쓴다
    @Spy
    private JsonMapper jsonMapper = JsonMapper.builder().build();

    @InjectMocks
    private SlotCommandOutbox slotCommandOutbox;

    @Test
    @DisplayName("새 eventId로 slot-commands에 기록하고, key는 slotId, payload에 eventId·eventType을 담는다")
    void recordWithNewEventId() {
        Reservation reservation = reservationIn(ReservationStatus.HELD, referralIn(ReferralStatus.PENDING, UUID.randomUUID()));

        UUID eventId = slotCommandOutbox.recordOutbox(reservation, "BookSlot");

        OutboxEvent outbox = savedOutbox();
        assertThat(outbox.getId()).isEqualTo(eventId);
        assertThat(outbox.getEventType()).isEqualTo("BookSlot");
        assertThat(outbox.getTopic()).isEqualTo("slot-commands");
        assertThat(outbox.getMessageKey()).isEqualTo(reservation.getSlotId().toString());
        assertThat(outbox.getAggregateType()).isEqualTo("RESERVATION");
        assertThat(outbox.getAggregateId()).isEqualTo(reservation.getId());

        JsonNode payload = jsonMapper.readTree(outbox.getPayload());
        assertThat(payload.get("eventId").asString()).isEqualTo(eventId.toString());
        assertThat(payload.get("eventType").asString()).isEqualTo("BookSlot");
        assertThat(payload.get("reservationId").asString()).isEqualTo(reservation.getId().toString());
        assertThat(payload.get("referralId").asString()).isEqualTo(reservation.getReferral().getId().toString());
        assertThat(payload.get("slotId").asString()).isEqualTo(reservation.getSlotId().toString());
        assertThat(payload.get("reservationStatus").asString()).isEqualTo("HELD");
        assertThat(payload.get("occurredAt").isNull()).isFalse();
    }

    @Test
    @DisplayName("넘겨받은 eventId가 있으면 그 값을 Outbox id와 payload에 그대로 쓴다")
    void recordWithGivenEventId() {
        Reservation reservation = reservationIn(ReservationStatus.REQUESTED, referralIn(ReferralStatus.PENDING, UUID.randomUUID()));
        UUID eventId = UUID.randomUUID();

        slotCommandOutbox.recordOutbox(reservation, eventId, "HoldRequested");

        OutboxEvent outbox = savedOutbox();
        assertThat(outbox.getId()).isEqualTo(eventId);
        assertThat(outbox.getEventType()).isEqualTo("HoldRequested");
        assertThat(jsonMapper.readTree(outbox.getPayload()).get("eventId").asString()).isEqualTo(eventId.toString());
    }

    private OutboxEvent savedOutbox() {
        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository).save(captor.capture());
        return captor.getValue();
    }
}
