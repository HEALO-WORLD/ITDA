package healoworld.slot_service.support.fixture;

import healoworld.slot_service.domain.entity.Slot;
import healoworld.slot_service.domain.enums.SlotStatus;
import healoworld.slot_service.support.FixedClockConfig;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.UUID;

public final class SlotFixture {

    public static final LocalDate DEFAULT_DATE = LocalDate.of(2026, 11, 2);
    public static final LocalTime DEFAULT_START_TIME = LocalTime.of(9, 0);
    public static final LocalTime DEFAULT_END_TIME = LocalTime.of(9, 30);

    private SlotFixture() {
    }

    public static Slot available() {
        return Slot.create(
                UUID.randomUUID(),
                UUID.randomUUID(),
                DEFAULT_DATE,
                DEFAULT_START_TIME,
                DEFAULT_END_TIME
        );
    }

    /** 지정한 예약으로 HOLD된 슬롯. heldUntil을 직접 정할 수 있어 만료 테스트(#C)에 사용 */
    public static Slot held(UUID reservationId, LocalDateTime heldUntil) {
        Slot slot = available();
        slot.hold(reservationId, heldUntil.minus(Slot.HOLD_DURATION));
        return slot;
    }

    /** 고정 시각(FixedClockConfig.NOW) 기준으로 방금 HOLD된 슬롯 */
    public static Slot held() {
        return held(UUID.randomUUID(), FixedClockConfig.NOW.plus(Slot.HOLD_DURATION));
    }

    public static Slot booked(UUID reservationId) {
        Slot slot = available();
        ReflectionTestUtils.setField(slot, "status", SlotStatus.BOOKED);
        ReflectionTestUtils.setField(slot, "reservationId", reservationId);
        return slot;
    }

    public static Slot unavailable() {
        Slot slot = available();
        ReflectionTestUtils.setField(slot, "status", SlotStatus.UNAVAILABLE);
        return slot;
    }

    public static Slot withStatus(SlotStatus status) {
        return switch (status) {
            case AVAILABLE -> available();
            case HELD -> held();
            case BOOKED -> booked(UUID.randomUUID());
            case UNAVAILABLE -> unavailable();
        };
    }
}