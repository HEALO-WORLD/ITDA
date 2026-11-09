package healoworld.slot_service.application;

import healoworld.slot_service.support.PostgresContainerSupport;

import healoworld.slot_service.application.dto.ReleaseOutcome;
import healoworld.slot_service.application.service.SlotReleaseService;
import healoworld.slot_service.domain.entity.Slot;
import healoworld.slot_service.domain.entity.SlotStatusHistory;
import healoworld.slot_service.domain.enums.SlotStatus;
import healoworld.slot_service.domain.enums.SlotStatusChangeReason;
import healoworld.slot_service.global.exception.BaseException;
import healoworld.slot_service.global.exception.ErrorCode;
import healoworld.slot_service.global.outbox.OutboxEventRepository;
import healoworld.slot_service.infrastructure.repository.SlotRepository;
import healoworld.slot_service.infrastructure.repository.SlotStatusHistoryRepository;
import healoworld.slot_service.support.FixedClockConfig;
import healoworld.slot_service.support.fixture.SlotFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.UUID;

import static healoworld.slot_service.support.SlotStatusHistoryTestSupport.singleHistory;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Import(FixedClockConfig.class)
class SlotReleaseServiceTest extends PostgresContainerSupport {

    @Autowired
    private SlotReleaseService slotReleaseService;

    @Autowired
    private SlotRepository slotRepository;

    @Autowired
    private SlotStatusHistoryRepository slotStatusHistoryRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @AfterEach
    void tearDown() {
        outboxEventRepository.deleteAllInBatch();
        slotStatusHistoryRepository.deleteAllInBatch();
        slotRepository.deleteAllInBatch();
    }

    // ---------- ReleaseHold ----------

    @Test
    void ReleaseHold_같은_예약으로_HELD면_AVAILABLE로_반환한다() {
        UUID reservationId = UUID.randomUUID();
        Slot slot = slotRepository.saveAndFlush(SlotFixture.held(reservationId, FixedClockConfig.NOW.plusMinutes(3)));

        ReleaseOutcome outcome = slotReleaseService.releaseHold(slot.getId(), reservationId, UUID.randomUUID());

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

        ReleaseOutcome outcome = slotReleaseService.releaseHold(slot.getId(), reservationId, UUID.randomUUID());

        assertThat(outcome).isEqualTo(ReleaseOutcome.RELEASED);
        assertThat(slotRepository.findById(slot.getId()).orElseThrow().getStatus()).isEqualTo(SlotStatus.AVAILABLE);
    }

    @Test
    void ReleaseHold_다른_예약이_HELD한_슬롯은_풀지_않는다() {
        UUID otherReservationId = UUID.randomUUID();
        Slot slot = slotRepository.saveAndFlush(SlotFixture.held(otherReservationId, FixedClockConfig.NOW.plusMinutes(3)));

        ReleaseOutcome outcome = slotReleaseService.releaseHold(slot.getId(), UUID.randomUUID(), UUID.randomUUID());

        assertThat(outcome).isEqualTo(ReleaseOutcome.NOT_APPLICABLE);
        Slot saved = slotRepository.findById(slot.getId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(SlotStatus.HELD);
        assertThat(saved.getReservationId()).isEqualTo(otherReservationId);
    }

    @Test
    void ReleaseHold_이미_AVAILABLE이면_변경하지_않는다() {
        Slot slot = slotRepository.saveAndFlush(SlotFixture.available());

        ReleaseOutcome outcome = slotReleaseService.releaseHold(slot.getId(), UUID.randomUUID(), UUID.randomUUID());

        assertThat(outcome).isEqualTo(ReleaseOutcome.NOT_APPLICABLE);
        assertThat(slotRepository.findById(slot.getId()).orElseThrow().getStatus()).isEqualTo(SlotStatus.AVAILABLE);
    }

    @Test
    void ReleaseHold_같은_예약으로_BOOKED면_풀지_않고_BOOKED를_유지한다() {
        UUID reservationId = UUID.randomUUID();
        Slot slot = slotRepository.saveAndFlush(SlotFixture.booked(reservationId));

        ReleaseOutcome outcome = slotReleaseService.releaseHold(slot.getId(), reservationId, UUID.randomUUID());

        assertThat(outcome).isEqualTo(ReleaseOutcome.NOT_APPLICABLE);
        Slot saved = slotRepository.findById(slot.getId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(SlotStatus.BOOKED);
        assertThat(saved.getReservationId()).isEqualTo(reservationId);
    }

    @Test
    void ReleaseHold_없는_슬롯이면_예외_없이_NOT_APPLICABLE() {
        assertThat(slotReleaseService.releaseHold(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()))
                .isEqualTo(ReleaseOutcome.NOT_APPLICABLE);
    }

    // ---------- ReleaseSlot ----------

    @Test
    void ReleaseSlot_같은_예약으로_BOOKED면_AVAILABLE로_반환한다() {
        UUID reservationId = UUID.randomUUID();
        Slot slot = slotRepository.saveAndFlush(SlotFixture.booked(reservationId));

        ReleaseOutcome outcome = slotReleaseService.releaseSlot(slot.getId(), reservationId, UUID.randomUUID());

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

        ReleaseOutcome outcome = slotReleaseService.releaseSlot(slot.getId(), reservationId, UUID.randomUUID());

        assertThat(outcome).isEqualTo(ReleaseOutcome.DETACHED);
        Slot saved = slotRepository.findById(slot.getId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(SlotStatus.UNAVAILABLE);
        assertThat(saved.getReservationId()).isNull();
    }

    @Test
    void ReleaseSlot_다른_예약이_BOOKED한_슬롯은_풀지_않는다() {
        UUID otherReservationId = UUID.randomUUID();
        Slot slot = slotRepository.saveAndFlush(SlotFixture.booked(otherReservationId));

        ReleaseOutcome outcome = slotReleaseService.releaseSlot(slot.getId(), UUID.randomUUID(), UUID.randomUUID());

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

        ReleaseOutcome outcome = slotReleaseService.releaseSlot(slot.getId(), UUID.randomUUID(), UUID.randomUUID());

        assertThat(outcome).isEqualTo(ReleaseOutcome.NOT_APPLICABLE);
        assertThat(slotRepository.findById(slot.getId()).orElseThrow().getReservationId()).isEqualTo(otherReservationId);
    }

    @Test
    void ReleaseSlot_같은_예약으로_HELD면_풀지_않는다() {
        UUID reservationId = UUID.randomUUID();
        Slot slot = slotRepository.saveAndFlush(SlotFixture.held(reservationId, FixedClockConfig.NOW.plusMinutes(3)));

        ReleaseOutcome outcome = slotReleaseService.releaseSlot(slot.getId(), reservationId, UUID.randomUUID());

        assertThat(outcome).isEqualTo(ReleaseOutcome.NOT_APPLICABLE);   // 확정 전 HOLD 해제는 ReleaseHold 몫
        assertThat(slotRepository.findById(slot.getId()).orElseThrow().getStatus()).isEqualTo(SlotStatus.HELD);
    }

    @Test
    void ReleaseSlot_두_번_요청해도_두_번째는_변경하지_않는다() {
        UUID reservationId = UUID.randomUUID();
        Slot slot = slotRepository.saveAndFlush(SlotFixture.booked(reservationId));

        ReleaseOutcome first = slotReleaseService.releaseSlot(slot.getId(), reservationId, UUID.randomUUID());
        ReleaseOutcome second = slotReleaseService.releaseSlot(slot.getId(), reservationId, UUID.randomUUID());

        assertThat(first).isEqualTo(ReleaseOutcome.RELEASED);
        assertThat(second).isEqualTo(ReleaseOutcome.NOT_APPLICABLE);
    }

    @Test
    void ReleaseSlot_없는_슬롯이면_예외_없이_NOT_APPLICABLE() {
        assertThat(slotReleaseService.releaseSlot(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()))
                .isEqualTo(ReleaseOutcome.NOT_APPLICABLE);
    }

    // ---------- ADMIN 강제 해제 ----------

    @Test
    void 강제해제_같은_예약으로_HELD면_AVAILABLE로_반환한다() {
        UUID reservationId = UUID.randomUUID();
        Slot slot = slotRepository.saveAndFlush(SlotFixture.held(reservationId, FixedClockConfig.NOW.plusMinutes(3)));

        ReleaseOutcome outcome = slotReleaseService.forceRelease(slot.getId(), reservationId, UUID.randomUUID());

        assertThat(outcome).isEqualTo(ReleaseOutcome.RELEASED);
        Slot saved = slotRepository.findById(slot.getId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(SlotStatus.AVAILABLE);
        assertThat(saved.getReservationId()).isNull();
        assertThat(saved.getHeldUntil()).isNull();
    }

    @Test
    void 강제해제_같은_예약으로_BOOKED면_AVAILABLE로_반환한다() {
        UUID reservationId = UUID.randomUUID();
        Slot slot = slotRepository.saveAndFlush(SlotFixture.booked(reservationId));

        ReleaseOutcome outcome = slotReleaseService.forceRelease(slot.getId(), reservationId, UUID.randomUUID());

        assertThat(outcome).isEqualTo(ReleaseOutcome.RELEASED);
        Slot saved = slotRepository.findById(slot.getId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(SlotStatus.AVAILABLE);
        assertThat(saved.getReservationId()).isNull();
        assertThat(outboxEventRepository.findAll()).isEmpty();   // 이벤트는 발행하지 않는다
    }

    @Test
    void 강제해제_같은_예약으로_UNAVAILABLE이면_예약_연결만_끊는다() {
        UUID reservationId = UUID.randomUUID();
        Slot slot = SlotFixture.unavailable();
        ReflectionTestUtils.setField(slot, "reservationId", reservationId);
        slotRepository.saveAndFlush(slot);

        ReleaseOutcome outcome = slotReleaseService.forceRelease(slot.getId(), reservationId, UUID.randomUUID());

        assertThat(outcome).isEqualTo(ReleaseOutcome.DETACHED);
        Slot saved = slotRepository.findById(slot.getId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(SlotStatus.UNAVAILABLE);
        assertThat(saved.getReservationId()).isNull();
    }

    @Test
    void 강제해제_다른_예약이_잡은_슬롯이면_SLOT_NOT_RELEASABLE이고_변경하지_않는다() {
        UUID otherReservationId = UUID.randomUUID();
        Slot slot = slotRepository.saveAndFlush(SlotFixture.booked(otherReservationId));

        assertThatThrownBy(() -> slotReleaseService.forceRelease(slot.getId(), UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(BaseException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.SLOT_NOT_RELEASABLE);

        Slot saved = slotRepository.findById(slot.getId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(SlotStatus.BOOKED);
        assertThat(saved.getReservationId()).isEqualTo(otherReservationId);
    }

    @Test
    void 강제해제_이미_AVAILABLE이면_SLOT_NOT_RELEASABLE() {
        Slot slot = slotRepository.saveAndFlush(SlotFixture.available());

        assertThatThrownBy(() -> slotReleaseService.forceRelease(slot.getId(), UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(BaseException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.SLOT_NOT_RELEASABLE);
    }

    @Test
    void 강제해제_없는_슬롯이면_SLOT_NOT_FOUND() {
        assertThatThrownBy(() -> slotReleaseService.forceRelease(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(BaseException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.SLOT_NOT_FOUND);
    }

    // ---------- 상태 이력 ----------

    @Test
    void 이력_ReleaseHold로_반환하면_HELD에서_AVAILABLE로_이벤트_이력을_남긴다() {
        UUID reservationId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        Slot slot = slotRepository.saveAndFlush(SlotFixture.held(reservationId, FixedClockConfig.NOW.plusMinutes(3)));

        slotReleaseService.releaseHold(slot.getId(), reservationId, eventId);

        SlotStatusHistory history = singleHistory(slotStatusHistoryRepository);
        assertTransition(history, slot.getId(), reservationId,
                SlotStatus.HELD, SlotStatus.AVAILABLE, SlotStatusChangeReason.RELEASE_HOLD);
        assertThat(history.getSourceEventId()).isEqualTo(eventId);
        assertThat(history.getChangedBy()).isNull();
    }

    @Test
    void 이력_ReleaseSlot으로_반환하면_BOOKED에서_AVAILABLE로_이벤트_이력을_남긴다() {
        UUID reservationId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        Slot slot = slotRepository.saveAndFlush(SlotFixture.booked(reservationId));

        slotReleaseService.releaseSlot(slot.getId(), reservationId, eventId);

        SlotStatusHistory history = singleHistory(slotStatusHistoryRepository);
        assertTransition(history, slot.getId(), reservationId,
                SlotStatus.BOOKED, SlotStatus.AVAILABLE, SlotStatusChangeReason.RELEASE_SLOT);
        assertThat(history.getSourceEventId()).isEqualTo(eventId);
        assertThat(history.getChangedBy()).isNull();
    }

    @Test
    void 이력_ReleaseSlot으로_UNAVAILABLE_슬롯의_연결만_끊어도_이력을_남긴다() {
        UUID reservationId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        Slot slot = SlotFixture.unavailable();
        ReflectionTestUtils.setField(slot, "reservationId", reservationId);
        slotRepository.saveAndFlush(slot);

        slotReleaseService.releaseSlot(slot.getId(), reservationId, eventId);

        SlotStatusHistory history = singleHistory(slotStatusHistoryRepository);
        assertTransition(history, slot.getId(), reservationId,
                SlotStatus.UNAVAILABLE, SlotStatus.UNAVAILABLE, SlotStatusChangeReason.RELEASE_SLOT);
        assertThat(history.getSourceEventId()).isEqualTo(eventId);
    }

    @ParameterizedTest
    @EnumSource(value = SlotStatus.class, names = {"HELD", "BOOKED"})
    void 이력_강제해제하면_이전_상태와_관리자_ID를_남긴다(SlotStatus fromStatus) {
        UUID reservationId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        Slot slot = fromStatus == SlotStatus.HELD
                ? SlotFixture.held(reservationId, FixedClockConfig.NOW.plusMinutes(3))
                : SlotFixture.booked(reservationId);
        slotRepository.saveAndFlush(slot);

        slotReleaseService.forceRelease(slot.getId(), reservationId, adminId);

        SlotStatusHistory history = singleHistory(slotStatusHistoryRepository);
        assertTransition(history, slot.getId(), reservationId,
                fromStatus, SlotStatus.AVAILABLE, SlotStatusChangeReason.ADMIN_RELEASE);
        assertThat(history.getChangedBy()).isEqualTo(adminId);
        assertThat(history.getSourceEventId()).isNull();
    }

    @Test
    void 이력_강제해제로_UNAVAILABLE_슬롯의_연결만_끊어도_관리자_ID를_남긴다() {
        UUID reservationId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        Slot slot = SlotFixture.unavailable();
        ReflectionTestUtils.setField(slot, "reservationId", reservationId);
        slotRepository.saveAndFlush(slot);

        slotReleaseService.forceRelease(slot.getId(), reservationId, adminId);

        SlotStatusHistory history = singleHistory(slotStatusHistoryRepository);
        assertTransition(history, slot.getId(), reservationId,
                SlotStatus.UNAVAILABLE, SlotStatus.UNAVAILABLE, SlotStatusChangeReason.ADMIN_RELEASE);
        assertThat(history.getChangedBy()).isEqualTo(adminId);
    }

    @Test
    void 이력_해제할_대상이_아니면_이력을_남기지_않는다() {
        UUID otherReservationId = UUID.randomUUID();
        Slot held = slotRepository.saveAndFlush(SlotFixture.held(otherReservationId, FixedClockConfig.NOW.plusMinutes(3)));
        Slot booked = slotRepository.saveAndFlush(SlotFixture.booked(otherReservationId));

        slotReleaseService.releaseHold(held.getId(), UUID.randomUUID(), UUID.randomUUID());
        slotReleaseService.releaseSlot(booked.getId(), UUID.randomUUID(), UUID.randomUUID());
        assertThatThrownBy(() -> slotReleaseService.forceRelease(booked.getId(), UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(BaseException.class);

        assertThat(slotStatusHistoryRepository.findAll()).isEmpty();
    }

    // 슬롯·예약·상태 전이와 changed_at(= 슬롯 updated_at)을 확인한다
    private void assertTransition(SlotStatusHistory history, UUID slotId, UUID reservationId,
                                  SlotStatus from, SlotStatus to, SlotStatusChangeReason reason) {
        assertThat(history.getSlot().getId()).isEqualTo(slotId);
        assertThat(history.getReservationId()).isEqualTo(reservationId);
        assertThat(history.getFromStatus()).isEqualTo(from);
        assertThat(history.getToStatus()).isEqualTo(to);
        assertThat(history.getReason()).isEqualTo(reason);

        Slot saved = slotRepository.findById(slotId).orElseThrow();
        assertThat(history.getChangedAt()).isEqualTo(saved.getUpdatedAt()).isEqualTo(FixedClockConfig.NOW);
    }
}
