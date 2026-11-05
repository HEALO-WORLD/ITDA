package healoworld.referral_service.application.event.result;

import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;
import java.util.Optional;

public enum SlotResultType {
    SLOT_HELD("SlotHeld"), SLOT_HOLD_FAILED("SlotHoldFailed"), SLOT_BOOKED("SlotBooked"),
    SLOT_BOOK_FAILED("SlotBookFailed"), HOLD_EXPIRED("HoldExpired"), SLOTS_BLOCKED("SlotsBlocked");

    private final String value;
    SlotResultType(String value) {
        this.value = value;
    }

    public String value() { return value; }

    public static Optional<SlotResultType> from(String value) {
        return Arrays.stream(values()).filter(t -> t.value.equals(value)).findFirst();
    }
}
