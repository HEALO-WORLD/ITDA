package healoworld.slot_service.global.deadletter;

import healoworld.slot_service.global.config.KafkaConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

@Slf4j
@Component
@RequiredArgsConstructor
public class DeadLetterConsumer {
    private final DeadLetterEventRepository deadLetterEventRepository;

    @KafkaListener(topics = "slot-commands-dlt",
            groupId = "slot-service-dlt",
            containerFactory = KafkaConfig.DEAD_LETTER_CONTAINER_FACTORY)
    public void consume(ConsumerRecord<String, String> record) {
        String topic = stringHeader(record, KafkaHeaders.DLT_ORIGINAL_TOPIC);
        byte[] partition = header(record, KafkaHeaders.DLT_ORIGINAL_PARTITION);
        byte[] offset = header(record, KafkaHeaders.DLT_ORIGINAL_OFFSET);

        if (topic == null || partition == null || partition.length != Integer.BYTES
                || offset == null || offset.length != Long.BYTES) {
            // 여기서 예외를 던지면 무한 재시도로 파티션이 막히므로, 원래 위치를 모르는 메시지는 로그만 남기고 건너뛴다
            log.warn("원래 위치 헤더가 없는 DLT 메시지를 건너뜀 dlt={}-{}@{}", record.topic(), record.partition(), record.offset());
            return;
        }

        int originalPartition = ByteBuffer.wrap(partition).getInt();
        long originalOffset = ByteBuffer.wrap(offset).getLong();

        if (exists(topic, originalPartition, originalOffset)) return;

        save(DeadLetterEvent.create(topic, originalPartition, originalOffset,
                record.key(), stringHeader(record, "eventId"), stringHeader(record, "eventType"), record.value(),
                stringHeader(record, KafkaHeaders.DLT_EXCEPTION_CAUSE_FQCN),
                stringHeader(record, KafkaConfig.FAILURE_SUMMARY_HEADER)));
    }

    private byte[] header(ConsumerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : header.value();
    }

    private String stringHeader(ConsumerRecord<String, String> record, String name) {
        byte[] value = header(record, name);
        return value == null ? null : new String(value, StandardCharsets.UTF_8);
    }

    private boolean exists(String topic, int partition, long offset) {
        return deadLetterEventRepository.existsByOriginalTopicAndOriginalPartitionAndOriginalOffset(topic, partition, offset);
    }

    private void save(DeadLetterEvent event) {
        deadLetterEventRepository.save(event);
    }
}
