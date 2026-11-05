package healoworld.referral_service.application.event.command;

import com.fasterxml.jackson.annotation.JsonValue;

public enum SlotCommandType {
    HOLD_REQUESTED("HoldRequested"),
    BOOK_SLOT("BookSlot"),
    RELEASE_HOLD("ReleaseHold"),
    RELEASE_SLOT("ReleaseSlot");

    private final String value;
    SlotCommandType(String value) { this.value = value; }

    @JsonValue
    public String value() { return value; }
}
