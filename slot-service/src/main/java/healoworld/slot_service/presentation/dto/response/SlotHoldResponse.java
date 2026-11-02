package healoworld.slot_service.presentation.dto.response;

import healoworld.slot_service.application.dto.HoldResult;
import healoworld.slot_service.domain.enums.HoldFailureReason;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.UUID;

// POST /api/v1/slots/{slotId}/hold 응답
@Getter
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class SlotHoldResponse {

    @Schema(description = "슬롯 ID")
    private final UUID slotId;

    @Schema(description = "슬롯을 확보한 예약 ID")
    private final UUID reservationId;

    @Schema(description = "확보 성공 여부", example = "true")
    private final boolean success;

    @Schema(description = "실패 사유. 성공 시 null", nullable = true)
    private final HoldFailureReason failureReason;

    @Schema(description = "HOLD 만료 시각 (확보 시각 + 5분). 실패 시 null", example = "2026-11-02T09:05:00", nullable = true)
    private final LocalDateTime heldUntil;

    public static SlotHoldResponse from(HoldResult result) {
        return new SlotHoldResponse(
                result.getSlotId(),
                result.getReservationId(),
                result.isSuccess(),
                result.getFailureReason(),
                result.getHeldUntil()
        );
    }
}