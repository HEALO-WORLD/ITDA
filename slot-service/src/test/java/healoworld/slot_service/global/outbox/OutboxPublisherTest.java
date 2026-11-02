package healoworld.slot_service.global.outbox;

import healoworld.slot_service.support.FixedClockConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OutboxPublisherTest {

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    private OutboxPublisher outboxPublisher;

    @BeforeEach
    void setUp() {
        ZoneId zone = ZoneId.systemDefault();
        Clock clock = Clock.fixed(FixedClockConfig.NOW.atZone(zone).toInstant(), zone);
        outboxPublisher = new OutboxPublisher(outboxEventRepository, kafkaTemplate, clock);
    }

    @Test
    void 발행에_성공하면_고정된_시각으로_발행_완료를_기록한다() {
        OutboxEvent event = outboxEvent("SlotHeld");
        when(outboxEventRepository.findTop100ByPublishedAtIsNullOrderByCreatedAtAsc()).thenReturn(List.of(event));
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(success());

        outboxPublisher.publish();

        verify(outboxEventRepository).markPublished(event.getId(), FixedClockConfig.NOW);
        verify(outboxEventRepository, never()).increaseRetryCount(any(), any());
    }

    @Test
    void 발행에_실패하면_재시도_횟수를_올리고_뒤_이벤트는_보내지_않는다() {
        OutboxEvent first = outboxEvent("SlotHeld");
        OutboxEvent second = outboxEvent("HoldExpired");
        when(outboxEventRepository.findTop100ByPublishedAtIsNullOrderByCreatedAtAsc()).thenReturn(List.of(first, second));
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("브로커 응답 없음")));

        outboxPublisher.publish();

        verify(kafkaTemplate, times(1)).send(any(ProducerRecord.class));
        verify(outboxEventRepository).increaseRetryCount(first.getId(), FixedClockConfig.NOW);
        verify(outboxEventRepository, never()).markPublished(any(), any());
    }

    @Test
    void 레코드에_토픽_키_값과_eventId_eventType_헤더를_담아_보낸다() {
        OutboxEvent event = outboxEvent("SlotHeld");
        when(outboxEventRepository.findTop100ByPublishedAtIsNullOrderByCreatedAtAsc()).thenReturn(List.of(event));
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(success());

        outboxPublisher.publish();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<ProducerRecord<String, String>> captor = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(captor.capture());
        ProducerRecord<String, String> record = captor.getValue();

        assertThat(record.topic()).isEqualTo("slot-results");
        assertThat(record.key()).isEqualTo(event.getMessageKey());
        assertThat(record.value()).isEqualTo(event.getPayload());
        assertThat(header(record, "eventId")).isEqualTo(event.getId().toString());
        assertThat(header(record, "eventType")).isEqualTo("SlotHeld");
    }

    private OutboxEvent outboxEvent(String eventType) {
        UUID slotId = UUID.randomUUID();
        return OutboxEvent.create(UUID.randomUUID(), "SLOT", slotId, "slot-results",
                slotId.toString(), eventType, "{\"slotId\":\"" + slotId + "\"}", FixedClockConfig.NOW);
    }

    private CompletableFuture<SendResult<String, String>> success() {
        return CompletableFuture.completedFuture(null);
    }

    private String header(ProducerRecord<String, String> record, String key) {
        return new String(record.headers().lastHeader(key).value(), StandardCharsets.UTF_8);
    }
}
