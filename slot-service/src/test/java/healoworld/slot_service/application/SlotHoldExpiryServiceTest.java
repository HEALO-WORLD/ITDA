package healoworld.slot_service.application;

import healoworld.slot_service.application.service.SlotHoldExpiryService;
import healoworld.slot_service.domain.entity.Slot;
import healoworld.slot_service.domain.entity.SlotStatusHistory;
import healoworld.slot_service.domain.enums.SlotStatus;
import healoworld.slot_service.domain.enums.SlotStatusChangeReason;
import healoworld.slot_service.global.outbox.OutboxEvent;
import healoworld.slot_service.global.outbox.OutboxEventRepository;
import healoworld.slot_service.infrastructure.repository.SlotRepository;
import healoworld.slot_service.infrastructure.repository.SlotStatusHistoryRepository;
import healoworld.slot_service.support.FixedClockConfig;
import healoworld.slot_service.support.fixture.SlotFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static healoworld.slot_service.support.SlotStatusHistoryTestSupport.singleHistory;
import static org.assertj.core.api.Assertions.assertThat;

// 고정 시각 FixedClockConfig.NOW(2026-10-30T10:00) 기준으로 HOLD 만료를 검증한다
@SpringBootTest
@ActiveProfiles("test")
@Import(FixedClockConfig.class)
class SlotHoldExpiryServiceTest {

    private static final LocalDateTime NOW = FixedClockConfig.NOW;

    @Autowired
    private SlotHoldExpiryService slotHoldExpiryService;

    @Autowired
    private SlotRepository slotRepository;

    @Autowired
    private SlotStatusHistoryRepository slotStatusHistoryRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private JsonMapper jsonMapper;

    @AfterEach
    void tearDown() {
        outboxEventRepository.deleteAllInBatch();
        slotStatusHistoryRepository.deleteAllInBatch();
        slotRepository.deleteAllInBatch();
    }

    @Test
    void 만료시각이_지난_HOLD는_AVAILABLE로_되돌리고_HoldExpired를_Outbox에_기록한다() {
        UUID reservationId = UUID.randomUUID();
        LocalDateTime heldUntil = NOW.minusSeconds(1);
        Slot slot = slotRepository.saveAndFlush(SlotFixture.held(reservationId, heldUntil));

        boolean expired = slotHoldExpiryService.expire(slot.getId(), reservationId, heldUntil);

        assertThat(expired).isTrue();
        Slot saved = slotRepository.findById(slot.getId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(SlotStatus.AVAILABLE);
        assertThat(saved.getReservationId()).isNull();
        assertThat(saved.getHeldUntil()).isNull();

        OutboxEvent outbox = singleOutbox();
        assertThat(outbox.getTopic()).isEqualTo("slot-results");
        assertThat(outbox.getMessageKey()).isEqualTo(slot.getId().toString());
        assertThat(outbox.getEventType()).isEqualTo("HoldExpired");
        assertThat(outbox.getCreatedAt()).isEqualTo(NOW);

        JsonNode payload = jsonMapper.readTree(outbox.getPayload());
        assertThat(payload.get("eventId").asText()).isEqualTo(outbox.getId().toString());
        assertThat(payload.get("eventType").asText()).isEqualTo("HoldExpired");
        assertThat(payload.get("occurredAt").asText()).isEqualTo("2026-10-30T10:00:00");
        assertThat(payload.get("slotId").asText()).isEqualTo(slot.getId().toString());
        assertThat(payload.get("reservationId").asText()).isEqualTo(reservationId.toString());
        assertThat(payload.get("heldUntil").asText()).isEqualTo("2026-10-30T09:59:59");
        assertThat(payload.has("referralId")).isFalse();
    }

    @Test
    void 만료시각과_현재시각이_같으면_만료로_처리한다() {
        UUID reservationId = UUID.randomUUID();
        Slot slot = slotRepository.saveAndFlush(SlotFixture.held(reservationId, NOW));

        boolean expired = slotHoldExpiryService.expire(slot.getId(), reservationId, NOW);

        assertThat(expired).isTrue();
        assertThat(slotRepository.findById(slot.getId()).orElseThrow().getStatus())
                .isEqualTo(SlotStatus.AVAILABLE);
    }

    @Test
    void 같은_슬롯을_두_번_처리해도_HoldExpired는_한_번만_기록한다() {
        UUID reservationId = UUID.randomUUID();
        LocalDateTime heldUntil = NOW.minusMinutes(1);
        Slot slot = slotRepository.saveAndFlush(SlotFixture.held(reservationId, heldUntil));

        boolean first = slotHoldExpiryService.expire(slot.getId(), reservationId, heldUntil);
        boolean second = slotHoldExpiryService.expire(slot.getId(), reservationId, heldUntil);

        assertThat(first).isTrue();
        assertThat(second).isFalse();
        assertThat(outboxEventRepository.findAll()).hasSize(1);
    }

    @Test
    void 만료시각이_지나지_않은_HOLD는_그대로_둔다() {
        UUID reservationId = UUID.randomUUID();
        LocalDateTime heldUntil = NOW.plusSeconds(1);
        Slot slot = slotRepository.saveAndFlush(SlotFixture.held(reservationId, heldUntil));

        boolean expired = slotHoldExpiryService.expire(slot.getId(), reservationId, heldUntil);

        assertThat(expired).isFalse();
        Slot saved = slotRepository.findById(slot.getId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(SlotStatus.HELD);
        assertThat(saved.getReservationId()).isEqualTo(reservationId);
        assertThat(outboxEventRepository.findAll()).isEmpty();
    }

    @Test
    void 이미_BOOKED로_확정된_슬롯은_해제하지_않는다() {
        UUID reservationId = UUID.randomUUID();
        Slot slot = SlotFixture.booked(reservationId);
        ReflectionTestUtils.setField(slot, "heldUntil", NOW.minusMinutes(1));   // 확정 전 만료시각이 남아 있는 경우
        slotRepository.saveAndFlush(slot);

        boolean expired = slotHoldExpiryService.expire(slot.getId(), reservationId, NOW.minusMinutes(1));

        assertThat(expired).isFalse();
        Slot saved = slotRepository.findById(slot.getId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(SlotStatus.BOOKED);
        assertThat(saved.getReservationId()).isEqualTo(reservationId);
        assertThat(outboxEventRepository.findAll()).isEmpty();
    }

    @Test
    void 다른_예약으로_다시_HOLD된_슬롯은_해제하지_않는다() {
        UUID oldReservationId = UUID.randomUUID();
        UUID newReservationId = UUID.randomUUID();
        LocalDateTime newHeldUntil = NOW.minusSeconds(1);   // 새 HOLD도 만료됐더라도 예약이 다르면 건드리지 않는다
        Slot slot = slotRepository.saveAndFlush(SlotFixture.held(newReservationId, newHeldUntil));

        boolean expired = slotHoldExpiryService.expire(slot.getId(), oldReservationId, NOW.minusMinutes(5));

        assertThat(expired).isFalse();
        Slot saved = slotRepository.findById(slot.getId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(SlotStatus.HELD);
        assertThat(saved.getReservationId()).isEqualTo(newReservationId);
        assertThat(outboxEventRepository.findAll()).isEmpty();
    }

    @Test
    void 만료_대상_조회는_만료된_HELD_슬롯만_오래된_순으로_반환한다() {
        Slot older = slotRepository.saveAndFlush(SlotFixture.held(UUID.randomUUID(), NOW.minusMinutes(3)));
        Slot newer = slotRepository.saveAndFlush(SlotFixture.held(UUID.randomUUID(), NOW.minusMinutes(1)));
        slotRepository.saveAndFlush(SlotFixture.held(UUID.randomUUID(), NOW.plusMinutes(1)));   // 아직 유효
        slotRepository.saveAndFlush(SlotFixture.booked(UUID.randomUUID()));
        slotRepository.saveAndFlush(SlotFixture.available());

        Slot deleted = SlotFixture.held(UUID.randomUUID(), NOW.minusMinutes(2));
        ReflectionTestUtils.setField(deleted, "deletedAt", NOW.minusMinutes(1));
        slotRepository.saveAndFlush(deleted);

        List<Slot> result = slotRepository.findExpiredHolds(SlotStatus.HELD, NOW, PageRequest.of(0, 100));

        assertThat(result).extracting(Slot::getId).containsExactly(older.getId(), newer.getId());
    }

    // ---------- 상태 이력 ----------

    @Test
    void 만료하면_HELD에서_AVAILABLE로_시스템_이력을_남긴다() {
        UUID reservationId = UUID.randomUUID();
        LocalDateTime heldUntil = NOW.minusSeconds(1);
        Slot slot = slotRepository.saveAndFlush(SlotFixture.held(reservationId, heldUntil));

        slotHoldExpiryService.expire(slot.getId(), reservationId, heldUntil);

        SlotStatusHistory history = singleHistory(slotStatusHistoryRepository);
        assertThat(history.getSlot().getId()).isEqualTo(slot.getId());
        assertThat(history.getReservationId()).isEqualTo(reservationId);   // 해제 전 예약 ID를 남긴다
        assertThat(history.getFromStatus()).isEqualTo(SlotStatus.HELD);
        assertThat(history.getToStatus()).isEqualTo(SlotStatus.AVAILABLE);
        assertThat(history.getReason()).isEqualTo(SlotStatusChangeReason.HOLD_EXPIRED);
        assertThat(history.getSourceEventId()).isNull();
        assertThat(history.getChangedBy()).isNull();

        Slot saved = slotRepository.findById(slot.getId()).orElseThrow();
        assertThat(history.getChangedAt()).isEqualTo(saved.getUpdatedAt()).isEqualTo(NOW);
    }

    @Test
    void 만료_대상이_아니면_이력을_남기지_않는다() {
        UUID reservationId = UUID.randomUUID();
        LocalDateTime heldUntil = NOW.plusSeconds(1);
        Slot slot = slotRepository.saveAndFlush(SlotFixture.held(reservationId, heldUntil));

        slotHoldExpiryService.expire(slot.getId(), reservationId, heldUntil);

        assertThat(slotStatusHistoryRepository.findAll()).isEmpty();
    }

    @Test
    void 같은_슬롯을_두_번_처리해도_이력은_한_번만_남긴다() {
        UUID reservationId = UUID.randomUUID();
        LocalDateTime heldUntil = NOW.minusMinutes(1);
        Slot slot = slotRepository.saveAndFlush(SlotFixture.held(reservationId, heldUntil));

        slotHoldExpiryService.expire(slot.getId(), reservationId, heldUntil);
        slotHoldExpiryService.expire(slot.getId(), reservationId, heldUntil);

        singleHistory(slotStatusHistoryRepository);
    }

    private OutboxEvent singleOutbox() {
        List<OutboxEvent> events = outboxEventRepository.findAll();
        assertThat(events).hasSize(1);
        return events.getFirst();
    }
}
