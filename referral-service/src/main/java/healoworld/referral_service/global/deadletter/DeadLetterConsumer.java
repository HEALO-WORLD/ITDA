package healoworld.referral_service.global.deadletter;

import healoworld.referral_service.global.config.KafkaConfig;
import lombok.extern.slf4j.Slf4j;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Slf4j
@Component
public class DeadLetterConsumer {
    // 저장한 실패 메시지 수 (원래 토픽별). 대시보드에서 적재 추이를 본다
    static final String SAVED_METRIC = "dead_letter.saved";
    // 처리되지 않은(PENDING) 메시지 수. Grafana 경고(DLT 미처리)는 이 값이 0이 될 때(관리자 재처리·무시)만 복구로 본다
    static final String PENDING_METRIC = "dead_letter.pending";
    // 이 서비스가 DLT로 받는 원래 토픽. 카운터를 0으로 미리 등록해야 재시작 후 첫 1건도 increase()에 잡힌다
    static final List<String> ORIGINAL_TOPICS = List.of("slot-results", "equipment-events");

    private final DeadLetterEventRepository deadLetterEventRepository;
    private final MeterRegistry meterRegistry;

    public DeadLetterConsumer(DeadLetterEventRepository deadLetterEventRepository, MeterRegistry meterRegistry) {
        this.deadLetterEventRepository = deadLetterEventRepository;
        this.meterRegistry = meterRegistry;
        ORIGINAL_TOPICS.forEach(this::savedCounter);
        // 값은 Prometheus가 수집할 때마다 count 쿼리로 읽는다 (15초 주기, 상태 인덱스 사용)
        Gauge.builder(PENDING_METRIC, deadLetterEventRepository,
                        repository -> repository.countByDeadLetterStatus(DeadLetterStatus.PENDING))
                .description("처리되지 않은(PENDING) DLT 메시지 수")
                .register(meterRegistry);
    }

    @KafkaListener(topics = {"slot-results-dlt", "equipment-events-dlt"},
            groupId = "referral-service-dlt",
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
        savedCounter(topic).increment();
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

    private Counter savedCounter(String originalTopic) {
        return Counter.builder(SAVED_METRIC)
                .description("DLT에서 저장한 실패 메시지 수")
                .tag("original_topic", originalTopic)
                .register(meterRegistry);
    }
}
