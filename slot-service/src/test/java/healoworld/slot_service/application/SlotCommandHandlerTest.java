package healoworld.slot_service.application;

import healoworld.slot_service.application.event.HoldRequestedEvent;
import healoworld.slot_service.application.service.SlotCommandHandler;
import healoworld.slot_service.domain.entity.Slot;
import healoworld.slot_service.domain.enums.SlotStatus;
import healoworld.slot_service.global.inbox.ConsumedEventId;
import healoworld.slot_service.global.inbox.ConsumedEventRepository;
import healoworld.slot_service.global.outbox.OutboxEvent;
import healoworld.slot_service.global.outbox.OutboxEventRepository;
import healoworld.slot_service.global.outbox.OutboxPublisher;
import healoworld.slot_service.infrastructure.repository.SlotRepository;
import healoworld.slot_service.support.FixedClockConfig;
import healoworld.slot_service.support.fixture.SlotFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import(FixedClockConfig.class)
class SlotCommandHandlerTest {

    @Autowired
    private SlotCommandHandler slotCommandHandler;

    @Autowired
    private SlotRepository slotRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private ConsumedEventRepository consumedEventRepository;

    @Autowired
    private JsonMapper jsonMapper;

    // 테스트 중 스케줄러가 로컬 Kafka로 Outbox를 실제 발행하지 않도록 막는다
    @MockitoBean
    private OutboxPublisher outboxPublisher;

    @AfterEach
    void tearDown() {
        outboxEventRepository.deleteAllInBatch();
        consumedEventRepository.deleteAllInBatch();
        slotRepository.deleteAllInBatch();
    }

    @Test
    void AVAILABLE_슬롯이면_HELD로_바꾸고_SlotHeld를_Outbox에_기록한다() {
        Slot slot = slotRepository.saveAndFlush(SlotFixture.available());
        HoldRequestedEvent request = holdRequested(slot.getId());

        slotCommandHandler.handleHoldRequested(request);

        Slot saved = slotRepository.findById(slot.getId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(SlotStatus.HELD);
        assertThat(saved.getReservationId()).isEqualTo(request.reservationId());

        OutboxEvent outbox = singleOutbox();
        assertThat(outbox.getTopic()).isEqualTo("slot-results");
        assertThat(outbox.getMessageKey()).isEqualTo(slot.getId().toString());
        assertThat(outbox.getEventType()).isEqualTo("SlotHeld");
        assertThat(outbox.getId()).isNotEqualTo(request.eventId());
        assertThat(outbox.getCreatedAt()).isEqualTo(FixedClockConfig.NOW);

        JsonNode payload = jsonMapper.readTree(outbox.getPayload());
        assertThat(payload.get("eventId").asText()).isEqualTo(outbox.getId().toString());
        assertThat(payload.get("eventType").asText()).isEqualTo("SlotHeld");
        assertThat(payload.get("occurredAt").asText()).isEqualTo("2026-10-30T10:00:00");
        assertThat(payload.get("slotId").asText()).isEqualTo(slot.getId().toString());
        assertThat(payload.get("reservationId").asText()).isEqualTo(request.reservationId().toString());
        assertThat(payload.get("referralId").asText()).isEqualTo(request.referralId().toString());
        assertThat(payload.get("heldUntil").asText()).isEqualTo("2026-10-30T10:05:00");

        assertThat(consumedEventRepository.existsById(
                new ConsumedEventId(request.eventId(), SlotCommandHandler.CONSUMER))).isTrue();
    }

    @Test
    void 다른_예약이_잡은_슬롯이면_SlotHoldFailed_SLOT_CONFLICT를_기록한다() {
        Slot slot = slotRepository.saveAndFlush(SlotFixture.held());
        UUID originalReservationId = slot.getReservationId();
        HoldRequestedEvent request = holdRequested(slot.getId());

        slotCommandHandler.handleHoldRequested(request);

        Slot saved = slotRepository.findById(slot.getId()).orElseThrow();
        assertThat(saved.getReservationId()).isEqualTo(originalReservationId);

        OutboxEvent outbox = singleOutbox();
        assertThat(outbox.getEventType()).isEqualTo("SlotHoldFailed");
        JsonNode payload = jsonMapper.readTree(outbox.getPayload());
        assertThat(payload.get("failureReason").asText()).isEqualTo("SLOT_CONFLICT");
        assertThat(payload.get("reservationId").asText()).isEqualTo(request.reservationId().toString());
    }

    @Test
    void 없는_슬롯이면_예외_없이_SlotHoldFailed를_기록하고_처리_완료로_남긴다() {
        HoldRequestedEvent request = holdRequested(UUID.randomUUID());

        slotCommandHandler.handleHoldRequested(request);

        OutboxEvent outbox = singleOutbox();
        assertThat(outbox.getEventType()).isEqualTo("SlotHoldFailed");
        assertThat(outbox.getMessageKey()).isEqualTo(request.slotId().toString());
        assertThat(jsonMapper.readTree(outbox.getPayload()).get("failureReason").asText())
                .isEqualTo("SLOT_CONFLICT");
        assertThat(consumedEventRepository.existsById(
                new ConsumedEventId(request.eventId(), SlotCommandHandler.CONSUMER))).isTrue();
    }

    @Test
    void 같은_이벤트를_두_번_받아도_결과는_한_번만_기록한다() {
        Slot slot = slotRepository.saveAndFlush(SlotFixture.available());
        HoldRequestedEvent request = holdRequested(slot.getId());

        slotCommandHandler.handleHoldRequested(request);
        slotCommandHandler.handleHoldRequested(request);

        assertThat(outboxEventRepository.findAll()).hasSize(1);
        assertThat(consumedEventRepository.findAll()).hasSize(1);
    }

    private OutboxEvent singleOutbox() {
        List<OutboxEvent> events = outboxEventRepository.findAll();
        assertThat(events).hasSize(1);
        return events.getFirst();
    }

    private HoldRequestedEvent holdRequested(UUID slotId) {
        return new HoldRequestedEvent(UUID.randomUUID(), HoldRequestedEvent.TYPE, FixedClockConfig.NOW,
                slotId, UUID.randomUUID(), UUID.randomUUID());
    }
}
