package healoworld.slot_service.global.deadletter;

import healoworld.slot_service.global.config.KafkaConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.record.TimestampType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class DeadLetterConsumerTest {
    private static final String DLT_TOPIC = "slot-commands-dlt";
    private static final String ORIGINAL_TOPIC = "slot-commands";
    private static final int ORIGINAL_PARTITION = 2;
    private static final long ORIGINAL_OFFSET = 5_000_000_000L; // int 범위를 넘는 오프셋도 그대로 저장되는지 확인
    private static final String KEY = "reservation-1";
    private static final String PAYLOAD = "{\"slotIds\":[\"not-a-uuid\"]}";

    @Mock
    private DeadLetterEventRepository deadLetterEventRepository;

    @InjectMocks
    private DeadLetterConsumer deadLetterConsumer;

    @Test
    @DisplayName("DLT 레코드 자체의 위치가 아니라 헤더의 원래 위치와 실패 정보를 PENDING으로 저장한다")
    void savesOriginalLocationAndFailureInfo() {
        RecordHeaders headers = originalLocationHeaders();
        addString(headers, "eventId", "not-a-uuid");
        addString(headers, "eventType", "HoldSlots");
        addString(headers, KafkaHeaders.DLT_EXCEPTION_CAUSE_FQCN, "java.lang.IllegalArgumentException");
        addString(headers, KafkaConfig.FAILURE_SUMMARY_HEADER, "IllegalArgumentException");

        deadLetterConsumer.consume(dltRecord(headers));

        DeadLetterEvent saved = captureSaved();
        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getOriginalTopic()).isEqualTo(ORIGINAL_TOPIC);
        assertThat(saved.getOriginalPartition()).isEqualTo(ORIGINAL_PARTITION);
        assertThat(saved.getOriginalOffset()).isEqualTo(ORIGINAL_OFFSET);
        assertThat(saved.getMessageKey()).isEqualTo(KEY);
        assertThat(saved.getEventId()).isEqualTo("not-a-uuid");
        assertThat(saved.getEventType()).isEqualTo("HoldSlots");
        assertThat(saved.getPayload()).isEqualTo(PAYLOAD);
        assertThat(saved.getExceptionClass()).isEqualTo("java.lang.IllegalArgumentException");
        assertThat(saved.getFailureSummary()).isEqualTo("IllegalArgumentException");
        assertThat(saved.getDeadLetterStatus()).isEqualTo(DeadLetterStatus.PENDING);
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getResolvedAt()).isNull();
        assertThat(saved.getResolvedBy()).isNull();
    }

    @Test
    @DisplayName("선택 헤더(eventId·eventType·예외 클래스·실패 요약)가 없으면 예외 없이 null로 저장한다")
    void savesNullWhenOptionalHeadersMissing() {
        deadLetterConsumer.consume(dltRecord(originalLocationHeaders()));

        DeadLetterEvent saved = captureSaved();
        assertThat(saved.getEventId()).isNull();
        assertThat(saved.getEventType()).isNull();
        assertThat(saved.getExceptionClass()).isNull();
        assertThat(saved.getFailureSummary()).isNull();
    }

    @Test
    @DisplayName("같은 원래 위치의 메시지가 이미 저장돼 있으면 다시 저장하지 않는다")
    void skipsAlreadySaved() {
        given(deadLetterEventRepository.existsByOriginalTopicAndOriginalPartitionAndOriginalOffset(
                ORIGINAL_TOPIC, ORIGINAL_PARTITION, ORIGINAL_OFFSET)).willReturn(true);

        deadLetterConsumer.consume(dltRecord(originalLocationHeaders()));

        verify(deadLetterEventRepository, never()).save(any());
    }

    @Test
    @DisplayName("원래 위치 헤더가 없으면 예외를 던지지 않고(무한 재시도 방지) 저장 없이 건너뛴다")
    void skipsWhenOriginalLocationMissing() {
        RecordHeaders headers = new RecordHeaders();
        addString(headers, KafkaHeaders.DLT_ORIGINAL_TOPIC, ORIGINAL_TOPIC);
        // partition·offset 헤더 없음

        deadLetterConsumer.consume(dltRecord(headers));

        verifyNoInteractions(deadLetterEventRepository);
    }

    @Test
    @DisplayName("slot-service가 받는 slot-commands의 DLT를 별도 그룹·DLT 전용 팩토리로 구독한다")
    void listensToSlotCommandsDlt() throws NoSuchMethodException {
        KafkaListener listener = DeadLetterConsumer.class.getMethod("consume", ConsumerRecord.class)
                .getAnnotation(KafkaListener.class);

        // 토픽 이름은 원래 토픽(slot-commands) + "-dlt"(KafkaConfig.DLT_SUFFIX) 규칙과 같아야 실제 DLT를 읽는다
        assertThat(listener.topics()).containsExactly("slot-commands-dlt");
        assertThat(listener.groupId()).isEqualTo("slot-service-dlt");
        assertThat(listener.containerFactory()).isEqualTo(KafkaConfig.DEAD_LETTER_CONTAINER_FACTORY);
    }

    @Test
    @DisplayName("같은 이름의 헤더가 여러 개면 마지막 값을 쓴다")
    void usesLastHeaderValue() {
        RecordHeaders headers = originalLocationHeaders();
        addString(headers, "eventType", "Old");
        addString(headers, "eventType", "HoldSlots");

        deadLetterConsumer.consume(dltRecord(headers));

        assertThat(captureSaved().getEventType()).isEqualTo("HoldSlots");
    }

    // DeadLetterPublishingRecoverer와 같은 형식: topic은 UTF-8, partition은 4바이트 int, offset은 8바이트 long
    private RecordHeaders originalLocationHeaders() {
        RecordHeaders headers = new RecordHeaders();
        addString(headers, KafkaHeaders.DLT_ORIGINAL_TOPIC, ORIGINAL_TOPIC);
        headers.add(KafkaHeaders.DLT_ORIGINAL_PARTITION, ByteBuffer.allocate(Integer.BYTES).putInt(ORIGINAL_PARTITION).array());
        headers.add(KafkaHeaders.DLT_ORIGINAL_OFFSET, ByteBuffer.allocate(Long.BYTES).putLong(ORIGINAL_OFFSET).array());
        return headers;
    }

    private void addString(RecordHeaders headers, String name, String value) {
        headers.add(name, value.getBytes(StandardCharsets.UTF_8));
    }

    // DLT 토픽 자체의 위치(파티션 0, 오프셋 7)는 원래 위치와 일부러 다르게 둔다
    private ConsumerRecord<String, String> dltRecord(RecordHeaders headers) {
        return new ConsumerRecord<>(DLT_TOPIC, 0, 7L, ConsumerRecord.NO_TIMESTAMP,
                TimestampType.NO_TIMESTAMP_TYPE,
                ConsumerRecord.NULL_SIZE, ConsumerRecord.NULL_SIZE, KEY, PAYLOAD, headers, Optional.empty());
    }

    private DeadLetterEvent captureSaved() {
        ArgumentCaptor<DeadLetterEvent> captor = ArgumentCaptor.forClass(DeadLetterEvent.class);
        verify(deadLetterEventRepository).save(captor.capture());
        return captor.getValue();
    }
}
