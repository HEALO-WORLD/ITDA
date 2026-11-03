package healoworld.slot_service.application;

import healoworld.slot_service.application.dto.ReleaseOutcome;
import healoworld.slot_service.application.service.SlotReleaseService;
import healoworld.slot_service.domain.entity.Slot;
import healoworld.slot_service.domain.enums.SlotStatus;
import healoworld.slot_service.global.outbox.OutboxEventRepository;
import healoworld.slot_service.infrastructure.repository.SlotRepository;
import healoworld.slot_service.support.FixedClockConfig;
import healoworld.slot_service.support.fixture.SlotFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Import(FixedClockConfig.class)
class SlotReleaseServiceTest {

    @Autowired
    private SlotReleaseService slotReleaseService;

    @Autowired
    private SlotRepository slotRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @AfterEach
    void tearDown() {
        outboxEventRepository.deleteAllInBatch();
        slotRepository.deleteAllInBatch();
    }

    // ---------- ReleaseHold ----------

    @Test
    void ReleaseHold_같은_예약으로_HELD면_AVAILABLE로_반환한다() {
        UUID reservationId = UUID.randomUUID();
        Slot slot = slotRepository.saveAndFlush(SlotFixture.held(reservationId, FixedClockConfig.NOW.plusMinutes(3)));

        ReleaseOutcome outcome = slotReleaseService.releaseHold(slot.getId(), reservationId);

        assertThat(outcome).isEqualTo(ReleaseOutcome.RELEASED);
        Slot saved = slotRepository.findById(slot.getId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(SlotStatus.AVAILABLE);
        assertThat(saved.getReservationId()).isNull();
        assertThat(saved.getHeldUntil()).isNull();
        assertThat(outboxEventRepository.findAll()).isEmpty();   // 결과 이벤트는 발행하지 않는다
    }

    @Test
    void ReleaseHold_만료시각이_지난_HOLD도_같은_예약이면_반환한다() {
        UUID reservationId = UUID.randomUUID();
        Slot slot = slotRepository.saveAndFlush(SlotFixture.held(reservationId, FixedClockConfig.NOW.minusMinutes(1)));

        ReleaseOutcome outcome = slotReleaseService.releaseHold(slot.getId(), reservationId);

        assertThat(outcome).isEqualTo(ReleaseOutcome.RELEASED);
        assertThat(slotRepository.findById(slot.getId()).orElseThrow().getStatus()).isEqualTo(SlotStatus.AVAILABLE);
    }

    @Test
    void ReleaseHold_다른_예약이_HELD한_슬롯은_풀지_않는다() {
        UUID otherReservationId = UUID.randomUUID();
        Slot slot = slotRepository.saveAndFlush(SlotFixture.held(otherReservationId, FixedClockConfig.NOW.plusMinutes(3)));

        ReleaseOutcome outcome = slotReleaseService.releaseHold(slot.getId(), UUID.randomUUID());

        assertThat(outcome).isEqualTo(ReleaseOutcome.NOT_APPLICABLE);
        Slot saved = slotRepository.findById(slot.getId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(SlotStatus.HELD);
        assertThat(saved.getReservationId()).isEqualTo(otherReservationId);
    }

    @Test
    void ReleaseHold_이미_AVAILABLE이면_변경하지_않는다() {
        Slot slot = slotRepository.saveAndFlush(SlotFixture.available());

        ReleaseOutcome outcome = slotReleaseService.releaseHold(slot.getId(), UUID.randomUUID());

        assertThat(outcome).isEqualTo(ReleaseOutcome.NOT_APPLICABLE);
        assertThat(slotRepository.findById(slot.getId()).orElseThrow().getStatus()).isEqualTo(SlotStatus.AVAILABLE);
    }

    @Test
    void ReleaseHold_같은_예약으로_BOOKED면_풀지_않고_BOOKED를_유지한다() {
        UUID reservationId = UUID.randomUUID();
        Slot slot = slotRepository.saveAndFlush(SlotFixture.booked(reservationId));

        ReleaseOutcome outcome = slotReleaseService.releaseHold(slot.getId(), reservationId);

        assertThat(outcome).isEqualTo(ReleaseOutcome.NOT_APPLICABLE);
        Slot saved = slotRepository.findById(slot.getId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(SlotStatus.BOOKED);
        assertThat(saved.getReservationId()).isEqualTo(reservationId);
    }

    @Test
    void ReleaseHold_없는_슬롯이면_예외_없이_NOT_APPLICABLE() {
        assertThat(slotReleaseService.releaseHold(UUID.randomUUID(), UUID.randomUUID()))
                .isEqualTo(ReleaseOutcome.NOT_APPLICABLE);
    }

    // ---------- ReleaseSlot ----------

    @Test
    void ReleaseSlot_같은_예약으로_BOOKED면_AVAILABLE로_반환한다() {
        UUID reservationId = UUID.randomUUID();
        Slot slot = slotRepository.saveAndFlush(SlotFixture.booked(reservationId));

        ReleaseOutcome outcome = slotReleaseService.releaseSlot(slot.getId(), reservationId);

        assertThat(outcome).isEqualTo(ReleaseOutcome.RELEASED);
        Slot saved = slotRepository.findById(slot.getId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(SlotStatus.AVAILABLE);
        assertThat(saved.getReservationId()).isNull();
        assertThat(outboxEventRepository.findAll()).isEmpty();
    }

    @Test
    void ReleaseSlot_같은_예약으로_UNAVAILABLE이면_예약_연결만_끊고_UNAVAILABLE을_유지한다() {
        UUID reservationId = UUID.randomUUID();
        Slot slot = SlotFixture.unavailable();
        ReflectionTestUtils.setField(slot, "reservationId", reservationId);   // 장애로 막힌 확정 슬롯
        slotRepository.saveAndFlush(slot);

        ReleaseOutcome outcome = slotReleaseService.releaseSlot(slot.getId(), reservationId);

        assertThat(outcome).isEqualTo(ReleaseOutcome.DETACHED);
        Slot saved = slotRepository.findById(slot.getId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(SlotStatus.UNAVAILABLE);
        assertThat(saved.getReservationId()).isNull();
    }

    @Test
    void ReleaseSlot_다른_예약이_BOOKED한_슬롯은_풀지_않는다() {
        UUID otherReservationId = UUID.randomUUID();
        Slot slot = slotRepository.saveAndFlush(SlotFixture.booked(otherReservationId));

        ReleaseOutcome outcome = slotReleaseService.releaseSlot(slot.getId(), UUID.randomUUID());

        assertThat(outcome).isEqualTo(ReleaseOutcome.NOT_APPLICABLE);
        Slot saved = slotRepository.findById(slot.getId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(SlotStatus.BOOKED);
        assertThat(saved.getReservationId()).isEqualTo(otherReservationId);
    }

    @Test
    void ReleaseSlot_다른_예약의_UNAVAILABLE_슬롯은_연결을_끊지_않는다() {
        UUID otherReservationId = UUID.randomUUID();
        Slot slot = SlotFixture.unavailable();
        ReflectionTestUtils.setField(slot, "reservationId", otherReservationId);
        slotRepository.saveAndFlush(slot);

        ReleaseOutcome outcome = slotReleaseService.releaseSlot(slot.getId(), UUID.randomUUID());

        assertThat(outcome).isEqualTo(ReleaseOutcome.NOT_APPLICABLE);
        assertThat(slotRepository.findById(slot.getId()).orElseThrow().getReservationId()).isEqualTo(otherReservationId);
    }

    @Test
    void ReleaseSlot_같은_예약으로_HELD면_풀지_않는다() {
        UUID reservationId = UUID.randomUUID();
        Slot slot = slotRepository.saveAndFlush(SlotFixture.held(reservationId, FixedClockConfig.NOW.plusMinutes(3)));

        ReleaseOutcome outcome = slotReleaseService.releaseSlot(slot.getId(), reservationId);

        assertThat(outcome).isEqualTo(ReleaseOutcome.NOT_APPLICABLE);   // 확정 전 HOLD 해제는 ReleaseHold 몫
        assertThat(slotRepository.findById(slot.getId()).orElseThrow().getStatus()).isEqualTo(SlotStatus.HELD);
    }

    @Test
    void ReleaseSlot_두_번_요청해도_두_번째는_변경하지_않는다() {
        UUID reservationId = UUID.randomUUID();
        Slot slot = slotRepository.saveAndFlush(SlotFixture.booked(reservationId));

        ReleaseOutcome first = slotReleaseService.releaseSlot(slot.getId(), reservationId);
        ReleaseOutcome second = slotReleaseService.releaseSlot(slot.getId(), reservationId);

        assertThat(first).isEqualTo(ReleaseOutcome.RELEASED);
        assertThat(second).isEqualTo(ReleaseOutcome.NOT_APPLICABLE);
    }

    @Test
    void ReleaseSlot_없는_슬롯이면_예외_없이_NOT_APPLICABLE() {
        assertThat(slotReleaseService.releaseSlot(UUID.randomUUID(), UUID.randomUUID()))
                .isEqualTo(ReleaseOutcome.NOT_APPLICABLE);
    }
}
