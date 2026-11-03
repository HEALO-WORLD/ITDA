package healoworld.slot_service.presentation.dto.response;

import healoworld.slot_service.application.dto.ReleaseOutcome;
import healoworld.slot_service.domain.enums.SlotStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

// POST /api/v1/slots/{slotId}/release 응답
public record SlotReleaseResponse(

        @Schema(description = "슬롯 ID")
        UUID slotId,

        @Schema(description = "연결이 해제된 예약 ID")
        UUID releasedReservationId,

        @Schema(description = "처리 결과. RELEASED: AVAILABLE로 반환, DETACHED: 장애로 UNAVAILABLE인 슬롯에서 예약 연결만 해제",
                example = "RELEASED")
        ReleaseOutcome result,

        @Schema(description = "해제 후 슬롯 상태", example = "AVAILABLE")
        SlotStatus status
) {
}
