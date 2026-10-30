package healoworld.slot_service.slot.domain;

import healoworld.slot_service.domain.entity.Slot;
import healoworld.slot_service.domain.enums.SlotStatus;
import healoworld.slot_service.global.exception.BaseException;
import healoworld.slot_service.global.exception.ErrorCode;
import healoworld.slot_service.support.fixture.SlotFixture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SlotTest {

    private final LocalDateTime now = LocalDateTime.of(2026, 10, 30, 10, 0);

    @Test
    void AVAILABLE_슬롯을_HOLD하면_HELD와_5분_뒤_만료시각이_저장된다() {
        Slot slot = SlotFixture.available();
        UUID reservationId = UUID.randomUUID();

        slot.hold(reservationId, now);

        assertThat(slot.getStatus()).isEqualTo(SlotStatus.HELD);
        assertThat(slot.getReservationId()).isEqualTo(reservationId);
        assertThat(slot.getHeldUntil()).isEqualTo(now.plusMinutes(5));
    }

    @ParameterizedTest
    @EnumSource(value = SlotStatus.class, names = {"HELD", "BOOKED", "UNAVAILABLE"})
    void AVAILABLE이_아니면_HOLD할_수_없다(SlotStatus status) {
        Slot slot = SlotFixture.withStatus(status);

        assertThatThrownBy(() -> slot.hold(UUID.randomUUID(), now))
                .isInstanceOf(BaseException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.SLOT_NOT_AVAILABLE);
    }

    @Test
    void AVAILABLE_슬롯만_HOLD_가능하다() {
        assertThat(SlotFixture.available().isHoldable()).isTrue();
        assertThat(SlotFixture.held().isHoldable()).isFalse();
        assertThat(SlotFixture.booked(UUID.randomUUID()).isHoldable()).isFalse();
        assertThat(SlotFixture.unavailable().isHoldable()).isFalse();
    }
}