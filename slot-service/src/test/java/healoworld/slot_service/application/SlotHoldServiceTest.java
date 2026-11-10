package healoworld.slot_service.application;

import healoworld.slot_service.support.PostgresContainerSupport;

import healoworld.slot_service.application.dto.HoldResult;
import healoworld.slot_service.application.service.SlotHoldService;
import healoworld.slot_service.domain.entity.Slot;
import healoworld.slot_service.domain.entity.SlotStatusHistory;
import healoworld.slot_service.domain.enums.HoldFailureReason;
import healoworld.slot_service.domain.enums.SlotStatus;
import healoworld.slot_service.domain.enums.SlotStatusChangeReason;
import healoworld.slot_service.global.exception.BaseException;
import healoworld.slot_service.global.exception.ErrorCode;
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

import java.time.LocalDateTime;
import java.util.UUID;

import static healoworld.slot_service.support.SlotStatusHistoryTestSupport.singleHistory;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Import(FixedClockConfig.class)
class SlotHoldServiceTest extends PostgresContainerSupport {

    @Autowired
    private SlotHoldService slotHoldService;

    @Autowired
    private SlotRepository slotRepository;

    @Autowired
    private SlotStatusHistoryRepository slotStatusHistoryRepository;

    @AfterEach
    void tearDown() {
        slotStatusHistoryRepository.deleteAllInBatch();
        slotRepository.deleteAllInBatch();
    }

    @Test
    void HOLD에_성공하면_DB에_HELD와_만료시각이_저장된다() {
        Slot slot = slotRepository.saveAndFlush(SlotFixture.available());
        UUID reservationId = UUID.randomUUID();

        HoldResult result = slotHoldService.hold(slot.getId(), reservationId, UUID.randomUUID());

        Slot saved = slotRepository.findById(slot.getId()).orElseThrow();
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getHeldUntil()).isEqualTo(FixedClockConfig.NOW.plusMinutes(5));
        assertThat(saved.getStatus()).isEqualTo(SlotStatus.HELD);
        assertThat(saved.getReservationId()).isEqualTo(reservationId);
        assertThat(saved.getHeldUntil()).isEqualTo(FixedClockConfig.NOW.plusMinutes(5));
    }

    @Test
    void 이미_HELD인_슬롯은_SLOT_CONFLICT로_실패한다() {
        Slot slot = slotRepository.saveAndFlush(SlotFixture.held());

        HoldResult result = slotHoldService.hold(slot.getId(), UUID.randomUUID(), UUID.randomUUID());

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getFailureReason()).isEqualTo(HoldFailureReason.SLOT_CONFLICT);
    }

    @ParameterizedTest
    @EnumSource(value = SlotStatus.class, names = {"BOOKED", "UNAVAILABLE"})
    void BOOKED나_UNAVAILABLE인_슬롯은_SLOT_CONFLICT로_실패한다(SlotStatus status) {
        Slot slot = slotRepository.saveAndFlush(SlotFixture.withStatus(status));

        HoldResult result = slotHoldService.hold(slot.getId(), UUID.randomUUID(), UUID.randomUUID());

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getFailureReason()).isEqualTo(HoldFailureReason.SLOT_CONFLICT);
        assertThat(slotRepository.findById(slot.getId()).orElseThrow().getStatus()).isEqualTo(status);
    }

    @Test
    void 같은_예약으로_이미_HELD면_기존_만료시각으로_성공한다() {
        UUID reservationId = UUID.randomUUID();
        LocalDateTime originalHeldUntil = FixedClockConfig.NOW.plusMinutes(3);
        Slot slot = slotRepository.saveAndFlush(SlotFixture.held(reservationId, originalHeldUntil));

        HoldResult result = slotHoldService.hold(slot.getId(), reservationId, UUID.randomUUID());

        Slot saved = slotRepository.findById(slot.getId()).orElseThrow();
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.isAlreadyHeld()).isTrue();   // 메트릭에서 idempotent로 구분
        assertThat(result.getHeldUntil()).isEqualTo(originalHeldUntil);
        assertThat(saved.getHeldUntil()).isEqualTo(originalHeldUntil);
        assertThat(saved.getReservationId()).isEqualTo(reservationId);
    }

    @Test
    void HOLD에_실패하면_기존_예약_정보가_바뀌지_않는다() {
        UUID originalReservationId = UUID.randomUUID();
        Slot slot = slotRepository.saveAndFlush(
                SlotFixture.held(originalReservationId, FixedClockConfig.NOW.plusMinutes(5)));

        slotHoldService.hold(slot.getId(), UUID.randomUUID(), UUID.randomUUID());

        Slot saved = slotRepository.findById(slot.getId()).orElseThrow();
        assertThat(saved.getReservationId()).isEqualTo(originalReservationId);
        assertThat(saved.getHeldUntil()).isEqualTo(FixedClockConfig.NOW.plusMinutes(5));
    }

    @Test
    void 없는_슬롯은_SLOT_NOT_FOUND_예외가_발생한다() {
        assertThatThrownBy(() -> slotHoldService.hold(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(BaseException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.SLOT_NOT_FOUND);
    }

    // ---------- 상태 이력 ----------

    @Test
    void HOLD에_성공하면_AVAILABLE에서_HELD로_이벤트_이력을_남긴다() {
        Slot slot = slotRepository.saveAndFlush(SlotFixture.available());
        UUID reservationId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();

        slotHoldService.hold(slot.getId(), reservationId, eventId);

        SlotStatusHistory history = singleHistory(slotStatusHistoryRepository);
        assertThat(history.getSlot().getId()).isEqualTo(slot.getId());
        assertThat(history.getReservationId()).isEqualTo(reservationId);
        assertThat(history.getFromStatus()).isEqualTo(SlotStatus.AVAILABLE);
        assertThat(history.getToStatus()).isEqualTo(SlotStatus.HELD);
        assertThat(history.getReason()).isEqualTo(SlotStatusChangeReason.HOLD);
        assertThat(history.getSourceEventId()).isEqualTo(eventId);
        assertThat(history.getChangedBy()).isNull();

        // 슬롯의 updated_at과 이력의 changed_at은 같은 now를 쓴다
        Slot saved = slotRepository.findById(slot.getId()).orElseThrow();
        assertThat(history.getChangedAt()).isEqualTo(saved.getUpdatedAt()).isEqualTo(FixedClockConfig.NOW);
    }

    @Test
    void HOLD에_실패하면_이력을_남기지_않는다() {
        Slot slot = slotRepository.saveAndFlush(SlotFixture.held());

        slotHoldService.hold(slot.getId(), UUID.randomUUID(), UUID.randomUUID());

        assertThat(slotStatusHistoryRepository.findAll()).isEmpty();
    }

    @Test
    void 같은_예약으로_이미_HELD면_상태가_바뀌지_않았으므로_이력을_남기지_않는다() {
        UUID reservationId = UUID.randomUUID();
        Slot slot = slotRepository.saveAndFlush(SlotFixture.held(reservationId, FixedClockConfig.NOW.plusMinutes(3)));

        slotHoldService.hold(slot.getId(), reservationId, UUID.randomUUID());

        assertThat(slotStatusHistoryRepository.findAll()).isEmpty();
    }
}