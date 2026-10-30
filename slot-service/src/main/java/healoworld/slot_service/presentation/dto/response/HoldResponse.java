package healoworld.slot_service.presentation.dto.response;

import healoworld.slot_service.application.dto.HoldResult;
import healoworld.slot_service.domain.enums.HoldFailureReason;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class HoldResponse {

    private final UUID slotId;
    private final UUID reservationId;
    private final boolean success;
    private final HoldFailureReason failureReason;
    private final LocalDateTime heldUntil;

    public static HoldResponse from(HoldResult result) {
        return new HoldResponse(
                result.getSlotId(),
                result.getReservationId(),
                result.isSuccess(),
                result.getFailureReason(),
                result.getHeldUntil()
        );
    }
}