package healoworld.slot_service.global.outbox;

import healoworld.slot_service.application.service.EquipmentDowntimeWriter;
import healoworld.slot_service.infrastructure.repository.EquipmentDowntimeRepository;
import healoworld.slot_service.infrastructure.repository.SlotRepository;
import healoworld.slot_service.infrastructure.repository.SlotStatusHistoryRepository;
import healoworld.slot_service.support.FixedClockConfig;
import healoworld.slot_service.support.PostgresContainerSupport;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.kafka.KafkaContainer;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// 실제 Kafka 브로커(Testcontainers)로 장비 중단 Outbox 이벤트의 발행 형식과 재발행 시 eventId 유지를 확인한다
// 발행은 스케줄러를 기다리지 않고 OutboxPublisher.publish()를 직접 호출한다 (스케줄 간격을 1시간으로 늘려 끼어들지 않게 함)
@SpringBootTest(properties = {
        "outbox.publish.enabled=true",
        "outbox.publish.fixed-delay-ms=3600000"
})
@ActiveProfiles("test")
@Import(FixedClockConfig.class)
class EquipmentDowntimeKafkaIntegrationTest extends PostgresContainerSupport {

    private static final String TOPIC = "equipment-events";
    private static final LocalDate DAY = LocalDate.of(2026, 11, 10);

    @ServiceConnection
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka-native:3.8.0");

    static {
        KAFKA.start();
    }

    @Autowired private EquipmentDowntimeWriter equipmentDowntimeWriter;
    @Autowired private OutboxPublisher outboxPublisher;
    @Autowired private OutboxEventRepository outboxEventRepository;
    @Autowired private EquipmentDowntimeRepository equipmentDowntimeRepository;
    @Autowired private SlotRepository slotRepository;
    @Autowired private SlotStatusHistoryRepository slotStatusHistoryRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    @AfterEach
    void tearDown() {
        outboxEventRepository.deleteAllInBatch();
        slotStatusHistoryRepository.deleteAllInBatch();
        slotRepository.deleteAllInBatch();
        equipmentDowntimeRepository.deleteAllInBatch();
    }

    @Test
    void Outbox_이벤트가_equipment_events에_장비_ID_키와_eventId_eventType_헤더로_발행된다() {
        UUID equipmentId = UUID.randomUUID();
        equipmentDowntimeWriter.register(equipmentId, UUID.randomUUID(), DAY.atTime(10, 0), DAY.atTime(12, 0),
                "가상 사유", UUID.randomUUID());
        OutboxEvent outbox = outboxEventRepository.findAll().getFirst();

        outboxPublisher.publish();

        List<ConsumerRecord<String, String>> records = consume(outbox.getId(), 1);
        ConsumerRecord<String, String> record = records.getFirst();
        assertThat(record.key()).isEqualTo(equipmentId.toString());
        assertThat(header(record, "eventType")).isEqualTo("EquipmentDowntimeStarted");
        assertThat(header(record, "eventId")).isEqualTo(outbox.getId().toString());
        assertThat(record.value()).isEqualTo(outbox.getPayload());
        assertThat(outboxEventRepository.findById(outbox.getId()).orElseThrow().getPublishedAt())
                .isEqualTo(FixedClockConfig.NOW);
    }

    @Test
    void 같은_Outbox_행이_다시_발행돼도_eventId와_본문이_같아_중복으로_식별된다() {
        UUID equipmentId = UUID.randomUUID();
        equipmentDowntimeWriter.register(equipmentId, UUID.randomUUID(), DAY.atTime(10, 0), DAY.atTime(12, 0),
                "가상 사유", UUID.randomUUID());
        OutboxEvent outbox = outboxEventRepository.findAll().getFirst();
        outboxPublisher.publish();

        // 브로커 ack 뒤 발행 완료 기록 전에 서버가 죽은 상황: 미발행으로 남아 다음 회차에 다시 보낸다
        jdbcTemplate.update("update p_outbox_events set published_at = null where id = ?", outbox.getId());
        outboxPublisher.publish();

        List<ConsumerRecord<String, String>> records = consume(outbox.getId(), 2);
        assertThat(records).hasSize(2);
        assertThat(records).allSatisfy(record -> {
            assertThat(record.key()).isEqualTo(equipmentId.toString());
            assertThat(header(record, "eventId")).isEqualTo(outbox.getId().toString());
            assertThat(record.value()).isEqualTo(outbox.getPayload());
        });
    }

    // equipment-events에서 지정한 eventId 헤더를 가진 메시지를 expected건 모일 때까지 읽는다
    private List<ConsumerRecord<String, String>> consume(UUID eventId, int expected) {
        Map<String, Object> props = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        List<ConsumerRecord<String, String>> matched = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(TOPIC));
            long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
            while (matched.size() < expected && System.nanoTime() < deadline) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    if (eventId.toString().equals(header(record, "eventId"))) {
                        matched.add(record);
                    }
                }
            }
        }
        assertThat(matched).as("발행된 메시지 수").hasSize(expected);
        return matched;
    }

    private static String header(ConsumerRecord<String, String> record, String key) {
        var header = record.headers().lastHeader(key);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}
