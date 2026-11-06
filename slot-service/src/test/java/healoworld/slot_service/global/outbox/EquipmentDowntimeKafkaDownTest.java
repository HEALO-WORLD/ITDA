package healoworld.slot_service.global.outbox;

import healoworld.slot_service.application.dto.EquipmentDowntimeRegistration;
import healoworld.slot_service.application.service.EquipmentDowntimeWriter;
import healoworld.slot_service.domain.entity.Slot;
import healoworld.slot_service.domain.enums.SlotStatus;
import healoworld.slot_service.infrastructure.repository.EquipmentDowntimeRepository;
import healoworld.slot_service.infrastructure.repository.SlotRepository;
import healoworld.slot_service.infrastructure.repository.SlotStatusHistoryRepository;
import healoworld.slot_service.support.FixedClockConfig;
import healoworld.slot_service.support.PostgresContainerSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// Kafka 브로커에 연결할 수 없을 때: 장애 등록·슬롯 차단·Outbox 저장은 커밋되고,
// 이벤트는 미발행으로 남아 같은 행(같은 eventId)으로 다시 시도되는지 확인한다
// 아무도 듣지 않는 주소로 연결하고, 메타데이터 대기 시간을 줄여 테스트 시간을 짧게 한다
@SpringBootTest(properties = {
        "outbox.publish.enabled=true",
        "outbox.publish.fixed-delay-ms=3600000",
        "spring.kafka.bootstrap-servers=127.0.0.1:1",
        "spring.kafka.producer.properties.max.block.ms=1000"
})
@ActiveProfiles("test")
@Import(FixedClockConfig.class)
class EquipmentDowntimeKafkaDownTest extends PostgresContainerSupport {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 10);

    @Autowired private EquipmentDowntimeWriter equipmentDowntimeWriter;
    @Autowired private OutboxPublisher outboxPublisher;
    @Autowired private OutboxEventRepository outboxEventRepository;
    @Autowired private EquipmentDowntimeRepository equipmentDowntimeRepository;
    @Autowired private SlotRepository slotRepository;
    @Autowired private SlotStatusHistoryRepository slotStatusHistoryRepository;

    @AfterEach
    void tearDown() {
        outboxEventRepository.deleteAllInBatch();
        slotStatusHistoryRepository.deleteAllInBatch();
        slotRepository.deleteAllInBatch();
        equipmentDowntimeRepository.deleteAllInBatch();
    }

    @Test
    void Kafka가_중단돼도_등록은_성공하고_이벤트는_같은_eventId로_재시도_대기한다() {
        UUID equipmentId = UUID.randomUUID();
        Slot slot = slotRepository.saveAndFlush(Slot.create(equipmentId, UUID.randomUUID(), DAY,
                LocalTime.of(10, 0), LocalTime.of(10, 30)));

        // 등록은 Kafka에 접근하지 않으므로 브로커 상태와 관계없이 커밋된다
        EquipmentDowntimeRegistration result = equipmentDowntimeWriter.register(equipmentId, UUID.randomUUID(),
                DAY.atTime(9, 0), DAY.atTime(12, 0), "가상 사유", UUID.randomUUID());
        OutboxEvent outbox = outboxEventRepository.findAll().getFirst();

        outboxPublisher.publish();
        outboxPublisher.publish();

        OutboxEvent retried = outboxEventRepository.findById(outbox.getId()).orElseThrow();
        assertThat(retried.getPublishedAt()).isNull();
        // 컨텍스트 시작 직후 스케줄러의 첫 회차가 끼어들 수 있어 최소 횟수로 확인한다
        assertThat(retried.getRetryCount()).isGreaterThanOrEqualTo(2);
        assertThat(retried.getPayload()).isEqualTo(outbox.getPayload());
        assertThat(outboxEventRepository.count()).isEqualTo(1);   // 재시도해도 새 이벤트(새 eventId)를 만들지 않는다

        assertThat(equipmentDowntimeRepository.existsById(result.downtime().getId())).isTrue();
        assertThat(slotRepository.findById(slot.getId()).orElseThrow().getStatus()).isEqualTo(SlotStatus.UNAVAILABLE);
    }
}
