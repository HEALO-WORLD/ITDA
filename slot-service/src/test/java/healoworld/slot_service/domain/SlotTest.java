package healoworld.slot_service.domain;

import healoworld.slot_service.domain.entity.Slot;
import healoworld.slot_service.domain.enums.SlotStatus;
import healoworld.slot_service.support.fixture.SlotFixture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SlotTest {

    private final LocalDateTime now = LocalDateTime.of(2026, 10, 30, 10, 0);

    @Test
    void 같은_예약으로_HELD된_슬롯이면_isHeldBy가_true다() {
        UUID reservationId = UUID.randomUUID();
        Slot slot = SlotFixture.held(reservationId, now.plus(Slot.HOLD_DURATION));

        assertThat(slot.isHeldBy(reservationId)).isTrue();
    }

    @Test
    void 다른_예약으로_HELD된_슬롯이면_isHeldBy가_false다() {
        Slot slot = SlotFixture.held(UUID.randomUUID(), now.plus(Slot.HOLD_DURATION));

        assertThat(slot.isHeldBy(UUID.randomUUID())).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = SlotStatus.class, names = {"AVAILABLE", "BOOKED", "UNAVAILABLE"})
    void HELD가_아니면_isHeldBy가_false다(SlotStatus status) {
        UUID reservationId = UUID.randomUUID();
        Slot slot = status == SlotStatus.BOOKED
                ? SlotFixture.booked(reservationId)
                : SlotFixture.withStatus(status);

        assertThat(slot.isHeldBy(reservationId)).isFalse();
    }
}
