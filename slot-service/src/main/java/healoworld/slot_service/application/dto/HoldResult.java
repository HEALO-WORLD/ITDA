package healoworld.slot_service.application.dto;

import healoworld.slot_service.domain.enums.HoldFailureReason;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class HoldResult {

    private final UUID slotId;
    private final UUID reservationId;
    private final boolean success;
    private final HoldFailureReason failureReason;
    private final LocalDateTime heldUntil;

    public static HoldResult success(UUID slotId, UUID reservationId, LocalDateTime heldUntil) {
        return new HoldResult(slotId, reservationId, true, null, heldUntil);
    }

    public static HoldResult failed(UUID slotId, UUID reservationId, HoldFailureReason reason) {
        return new HoldResult(slotId, reservationId, false, reason, null);
    }
}