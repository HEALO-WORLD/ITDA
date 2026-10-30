package healoworld.slot_service.slot.application;

import healoworld.slot_service.application.dto.HoldResult;
import healoworld.slot_service.application.service.SlotHoldService;
import healoworld.slot_service.domain.entity.Slot;
import healoworld.slot_service.domain.enums.HoldFailureReason;
import healoworld.slot_service.domain.enums.SlotStatus;
import healoworld.slot_service.global.exception.BaseException;
import healoworld.slot_service.global.exception.ErrorCode;
import healoworld.slot_service.infrastructure.repository.SlotRepository;
import healoworld.slot_service.support.FixedClockConfig;
import healoworld.slot_service.support.fixture.SlotFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Import(FixedClockConfig.class)
class SlotHoldServiceTest {

    @Autowired
    private SlotHoldService slotHoldService;

    @Autowired
    private SlotRepository slotRepository;

    @AfterEach
    void tearDown() {
        slotRepository.deleteAllInBatch();
    }

    @Test
    void HOLD에_성공하면_DB에_HELD와_만료시각이_저장된다() {
        Slot slot = slotRepository.saveAndFlush(SlotFixture.available());
        UUID reservationId = UUID.randomUUID();

        HoldResult result = slotHoldService.hold(slot.getId(), reservationId);

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

        HoldResult result = slotHoldService.hold(slot.getId(), UUID.randomUUID());

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getFailureReason()).isEqualTo(HoldFailureReason.SLOT_CONFLICT);
    }

    @Test
    void HOLD에_실패하면_기존_예약_정보가_바뀌지_않는다() {
        UUID originalReservationId = UUID.randomUUID();
        Slot slot = slotRepository.saveAndFlush(
                SlotFixture.held(originalReservationId, FixedClockConfig.NOW.plusMinutes(5)));

        slotHoldService.hold(slot.getId(), UUID.randomUUID());

        Slot saved = slotRepository.findById(slot.getId()).orElseThrow();
        assertThat(saved.getReservationId()).isEqualTo(originalReservationId);
        assertThat(saved.getHeldUntil()).isEqualTo(FixedClockConfig.NOW.plusMinutes(5));
    }

    @Test
    void 없는_슬롯은_SLOT_NOT_FOUND_예외가_발생한다() {
        assertThatThrownBy(() -> slotHoldService.hold(UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(BaseException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.SLOT_NOT_FOUND);
    }
}