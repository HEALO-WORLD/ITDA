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
    private final boolean alreadyHeld;   // 같은 예약으로 이미 HELD였음 (중복 요청)
    private final HoldFailureReason failureReason;
    private final LocalDateTime heldUntil;

    public static HoldResult success(UUID slotId, UUID reservationId, LocalDateTime heldUntil) {
        return new HoldResult(slotId, reservationId, true, false, null, heldUntil);
    }

    public static HoldResult alreadyHeld(UUID slotId, UUID reservationId, LocalDateTime heldUntil) {
        return new HoldResult(slotId, reservationId, true, true, null, heldUntil);
    }

    public static HoldResult failed(UUID slotId, UUID reservationId, HoldFailureReason reason) {
        return new HoldResult(slotId, reservationId, false, false, reason, null);
    }
}