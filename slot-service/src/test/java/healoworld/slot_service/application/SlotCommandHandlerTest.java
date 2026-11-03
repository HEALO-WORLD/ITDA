package healoworld.slot_service.application;

import healoworld.slot_service.application.event.command.BookSlotEvent;
import healoworld.slot_service.application.event.command.HoldRequestedEvent;
import healoworld.slot_service.application.event.command.ReleaseHoldEvent;
import healoworld.slot_service.application.event.command.ReleaseSlotEvent;
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
        assertThat(payload.has("referralId")).isFalse();   // slot-results는 referralId를 담지 않는다
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

    @Test
    void 만료_전_HELD_슬롯이면_BOOKED로_확정하고_SlotBooked를_Outbox에_기록한다() {
        UUID reservationId = UUID.randomUUID();
        Slot slot = slotRepository.saveAndFlush(SlotFixture.held(reservationId, FixedClockConfig.NOW.plusMinutes(3)));
        BookSlotEvent request = bookSlot(slot.getId(), reservationId);

        slotCommandHandler.handleBookSlot(request);

        assertThat(slotRepository.findById(slot.getId()).orElseThrow().getStatus()).isEqualTo(SlotStatus.BOOKED);

        OutboxEvent outbox = singleOutbox();
        assertThat(outbox.getTopic()).isEqualTo("slot-results");
        assertThat(outbox.getMessageKey()).isEqualTo(slot.getId().toString());
        assertThat(outbox.getEventType()).isEqualTo("SlotBooked");
        assertThat(outbox.getId()).isNotEqualTo(request.eventId());

        JsonNode payload = jsonMapper.readTree(outbox.getPayload());
        assertThat(payload.get("eventId").asText()).isEqualTo(outbox.getId().toString());
        assertThat(payload.get("eventType").asText()).isEqualTo("SlotBooked");
        assertThat(payload.get("occurredAt").asText()).isEqualTo("2026-10-30T10:00:00");
        assertThat(payload.get("slotId").asText()).isEqualTo(slot.getId().toString());
        assertThat(payload.get("reservationId").asText()).isEqualTo(reservationId.toString());
        assertThat(payload.has("referralId")).isFalse();

        assertThat(consumedEventRepository.existsById(
                new ConsumedEventId(request.eventId(), SlotCommandHandler.CONSUMER))).isTrue();
    }

    @Test
    void 같은_예약으로_이미_BOOKED면_SlotBooked를_다시_기록한다() {
        UUID reservationId = UUID.randomUUID();
        Slot slot = slotRepository.saveAndFlush(SlotFixture.booked(reservationId));

        slotCommandHandler.handleBookSlot(bookSlot(slot.getId(), reservationId));

        assertThat(singleOutbox().getEventType()).isEqualTo("SlotBooked");
    }

    @Test
    void 다른_예약이_잡은_슬롯이면_SlotBookFailed를_기록한다() {
        Slot slot = slotRepository.saveAndFlush(SlotFixture.held());
        UUID reservationId = UUID.randomUUID();

        slotCommandHandler.handleBookSlot(bookSlot(slot.getId(), reservationId));

        OutboxEvent outbox = singleOutbox();
        assertThat(outbox.getEventType()).isEqualTo("SlotBookFailed");
        assertThat(outbox.getMessageKey()).isEqualTo(slot.getId().toString());
        JsonNode payload = jsonMapper.readTree(outbox.getPayload());
        assertThat(payload.get("reservationId").asText()).isEqualTo(reservationId.toString());
        assertThat(payload.has("referralId")).isFalse();
    }

    @Test
    void 없는_슬롯이면_예외_없이_SlotBookFailed를_기록하고_처리_완료로_남긴다() {
        BookSlotEvent request = bookSlot(UUID.randomUUID(), UUID.randomUUID());

        slotCommandHandler.handleBookSlot(request);

        assertThat(singleOutbox().getEventType()).isEqualTo("SlotBookFailed");
        assertThat(consumedEventRepository.existsById(
                new ConsumedEventId(request.eventId(), SlotCommandHandler.CONSUMER))).isTrue();
    }

    @Test
    void HOLD가_만료됐으면_확정하지_않고_HoldExpired만_기록한다() {
        UUID reservationId = UUID.randomUUID();
        Slot slot = slotRepository.saveAndFlush(SlotFixture.held(reservationId, FixedClockConfig.NOW.minusSeconds(1)));

        slotCommandHandler.handleBookSlot(bookSlot(slot.getId(), reservationId));

        assertThat(slotRepository.findById(slot.getId()).orElseThrow().getStatus()).isEqualTo(SlotStatus.AVAILABLE);
        OutboxEvent outbox = singleOutbox();   // SlotBookFailed는 기록하지 않는다
        assertThat(outbox.getEventType()).isEqualTo("HoldExpired");
        assertThat(outbox.getMessageKey()).isEqualTo(slot.getId().toString());
    }

    @Test
    void 같은_BookSlot을_두_번_받아도_결과는_한_번만_기록한다() {
        UUID reservationId = UUID.randomUUID();
        Slot slot = slotRepository.saveAndFlush(SlotFixture.held(reservationId, FixedClockConfig.NOW.plusMinutes(3)));
        BookSlotEvent request = bookSlot(slot.getId(), reservationId);

        slotCommandHandler.handleBookSlot(request);
        slotCommandHandler.handleBookSlot(request);

        assertThat(outboxEventRepository.findAll()).hasSize(1);
        assertThat(consumedEventRepository.findAll()).hasSize(1);
    }

    @Test
    void ReleaseHold_같은_예약의_HOLD를_반환하고_결과_이벤트_없이_처리_완료로_남긴다() {
        UUID reservationId = UUID.randomUUID();
        Slot slot = slotRepository.saveAndFlush(SlotFixture.held(reservationId, FixedClockConfig.NOW.plusMinutes(3)));
        ReleaseHoldEvent request = releaseHold(slot.getId(), reservationId);

        slotCommandHandler.handleReleaseHold(request);

        assertThat(slotRepository.findById(slot.getId()).orElseThrow().getStatus()).isEqualTo(SlotStatus.AVAILABLE);
        assertThat(outboxEventRepository.findAll()).isEmpty();
        assertThat(consumedEventRepository.existsById(
                new ConsumedEventId(request.eventId(), SlotCommandHandler.CONSUMER))).isTrue();
    }

    @Test
    void ReleaseHold_해제할_슬롯이_없어도_예외_없이_처리_완료로_남긴다() {
        ReleaseHoldEvent request = releaseHold(UUID.randomUUID(), UUID.randomUUID());

        slotCommandHandler.handleReleaseHold(request);

        assertThat(consumedEventRepository.existsById(
                new ConsumedEventId(request.eventId(), SlotCommandHandler.CONSUMER))).isTrue();
    }

    @Test
    void ReleaseHold_같은_이벤트를_두_번_받아도_한_번만_처리한다() {
        UUID reservationId = UUID.randomUUID();
        Slot slot = slotRepository.saveAndFlush(SlotFixture.held(reservationId, FixedClockConfig.NOW.plusMinutes(3)));
        ReleaseHoldEvent request = releaseHold(slot.getId(), reservationId);

        slotCommandHandler.handleReleaseHold(request);
        slotCommandHandler.handleReleaseHold(request);

        assertThat(consumedEventRepository.findAll()).hasSize(1);
    }

    @Test
    void ReleaseSlot_같은_예약의_BOOKED_슬롯을_반환하고_결과_이벤트_없이_처리_완료로_남긴다() {
        UUID reservationId = UUID.randomUUID();
        Slot slot = slotRepository.saveAndFlush(SlotFixture.booked(reservationId));
        ReleaseSlotEvent request = releaseSlot(slot.getId(), reservationId);

        slotCommandHandler.handleReleaseSlot(request);

        Slot saved = slotRepository.findById(slot.getId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(SlotStatus.AVAILABLE);
        assertThat(saved.getReservationId()).isNull();
        assertThat(outboxEventRepository.findAll()).isEmpty();
        assertThat(consumedEventRepository.existsById(
                new ConsumedEventId(request.eventId(), SlotCommandHandler.CONSUMER))).isTrue();
    }

    @Test
    void ReleaseSlot_같은_이벤트를_두_번_받아도_한_번만_처리한다() {
        UUID reservationId = UUID.randomUUID();
        Slot slot = slotRepository.saveAndFlush(SlotFixture.booked(reservationId));
        ReleaseSlotEvent request = releaseSlot(slot.getId(), reservationId);

        slotCommandHandler.handleReleaseSlot(request);
        slotCommandHandler.handleReleaseSlot(request);

        assertThat(consumedEventRepository.findAll()).hasSize(1);
    }

    private OutboxEvent singleOutbox() {
        List<OutboxEvent> events = outboxEventRepository.findAll();
        assertThat(events).hasSize(1);
        return events.getFirst();
    }

    private ReleaseHoldEvent releaseHold(UUID slotId, UUID reservationId) {
        return new ReleaseHoldEvent(UUID.randomUUID(), ReleaseHoldEvent.TYPE, FixedClockConfig.NOW, slotId, reservationId);
    }

    private ReleaseSlotEvent releaseSlot(UUID slotId, UUID reservationId) {
        return new ReleaseSlotEvent(UUID.randomUUID(), ReleaseSlotEvent.TYPE, FixedClockConfig.NOW, slotId, reservationId);
    }

    private BookSlotEvent bookSlot(UUID slotId, UUID reservationId) {
        return new BookSlotEvent(UUID.randomUUID(), BookSlotEvent.TYPE, FixedClockConfig.NOW, slotId, reservationId);
    }

    private HoldRequestedEvent holdRequested(UUID slotId) {
        return new HoldRequestedEvent(UUID.randomUUID(), HoldRequestedEvent.TYPE, FixedClockConfig.NOW,
                slotId, UUID.randomUUID());
    }
}
