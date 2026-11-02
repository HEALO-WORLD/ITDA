package healoworld.slot_service.presentation.consumer;

import healoworld.slot_service.application.event.HoldRequestedEvent;
import healoworld.slot_service.application.service.SlotCommandHandler;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class SlotCommandConsumerTest {

    @Mock
    private SlotCommandHandler slotCommandHandler;

    private SlotCommandConsumer consumer;

    private final UUID eventId = UUID.randomUUID();
    private final UUID slotId = UUID.randomUUID();
    private final UUID reservationId = UUID.randomUUID();
    private final UUID referralId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        consumer = new SlotCommandConsumer(slotCommandHandler, JsonMapper.builder().build());
    }

    @Test
    void HoldRequested를_읽어_핸들러로_넘긴다() {
        consumer.consume(record(slotId.toString(), "HoldRequested", holdRequestedJson(slotId)));

        ArgumentCaptor<HoldRequestedEvent> captor = ArgumentCaptor.forClass(HoldRequestedEvent.class);
        verify(slotCommandHandler).handleHoldRequested(captor.capture());
        HoldRequestedEvent event = captor.getValue();

        assertThat(event.eventId()).isEqualTo(eventId);
        assertThat(event.slotId()).isEqualTo(slotId);
        assertThat(event.reservationId()).isEqualTo(reservationId);
        assertThat(event.referralId()).isEqualTo(referralId);
        assertThat(event.occurredAt()).isEqualTo(LocalDateTime.of(2026, 11, 2, 10, 0));
    }

    @Test
    void 지원하지_않는_eventType이면_IllegalArgumentException() {
        assertThatThrownBy(() -> consumer.consume(record(slotId.toString(), "Unknown", holdRequestedJson(slotId))))
                .isInstanceOf(IllegalArgumentException.class);
        verify(slotCommandHandler, never()).handleHoldRequested(any());
    }

    @Test
    void eventType_헤더가_없으면_IllegalArgumentException() {
        assertThatThrownBy(() -> consumer.consume(record(slotId.toString(), null, holdRequestedJson(slotId))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void JSON이_깨져_있으면_JacksonException() {
        assertThatThrownBy(() -> consumer.consume(record(slotId.toString(), "HoldRequested", "{not-json")))
                .isInstanceOf(JacksonException.class);
    }

    @Test
    void 필수_필드가_없으면_IllegalArgumentException() {
        String json = """
                {"eventId":"%s","eventType":"HoldRequested","slotId":"%s"}
                """.formatted(eventId, slotId);

        assertThatThrownBy(() -> consumer.consume(record(slotId.toString(), "HoldRequested", json)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 메시지_키와_slotId가_다르면_IllegalArgumentException() {
        String otherKey = UUID.randomUUID().toString();

        assertThatThrownBy(() -> consumer.consume(record(otherKey, "HoldRequested", holdRequestedJson(slotId))))
                .isInstanceOf(IllegalArgumentException.class);
        verify(slotCommandHandler, never()).handleHoldRequested(any());
    }

    private String holdRequestedJson(UUID slotId) {
        return """
                {"eventId":"%s","eventType":"HoldRequested","occurredAt":"2026-11-02T10:00:00",
                 "slotId":"%s","reservationId":"%s","referralId":"%s"}
                """.formatted(eventId, slotId, reservationId, referralId);
    }

    private ConsumerRecord<String, String> record(String key, String eventType, String value) {
        ConsumerRecord<String, String> record = new ConsumerRecord<>("slot-commands", 0, 0L, key, value);
        record.headers().add("eventId", eventId.toString().getBytes(StandardCharsets.UTF_8));
        if (eventType != null) {
            record.headers().add("eventType", eventType.getBytes(StandardCharsets.UTF_8));
        }
        return record;
    }
}
