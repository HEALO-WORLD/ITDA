package healoworld.slot_service.presentation.dto.response;

import healoworld.slot_service.domain.entity.SlotStatusHistory;
import healoworld.slot_service.domain.enums.SlotStatus;
import healoworld.slot_service.domain.enums.SlotStatusChangeReason;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.UUID;

// GET /api/v1/slots/{slotId}/status-histories 응답 항목
public record SlotStatusHistoryResponse(

        @Schema(description = "상태 이력 ID")
        UUID id,

        @Schema(description = "슬롯 ID")
        UUID slotId,

        @Schema(description = "관련 예약 ID. 예약과 무관한 변경이면 NULL")
        UUID reservationId,

        @Schema(description = "이전 상태", example = "AVAILABLE")
        SlotStatus fromStatus,

        @Schema(description = "이후 상태", example = "HELD")
        SlotStatus toStatus,

        @Schema(description = "변경 사유", example = "HOLD")
        SlotStatusChangeReason reason,

        @Schema(description = "원인이 된 Kafka 이벤트 ID. API·스케줄러로 바뀌었으면 NULL")
        UUID sourceEventId,

        @Schema(description = "상태 변경 일시")
        LocalDateTime changedAt,

        @Schema(description = "변경한 사용자 ID. 이벤트·스케줄러로 바뀌었으면 NULL")
        UUID changedBy
) {
    // slot은 LAZY 프록시지만 getId()는 SELECT 없이 반환된다
    public static SlotStatusHistoryResponse from(SlotStatusHistory history) {
        return new SlotStatusHistoryResponse(
                history.getId(),
                history.getSlot().getId(),
                history.getReservationId(),
                history.getFromStatus(),
                history.getToStatus(),
                history.getReason(),
                history.getSourceEventId(),
                history.getChangedAt(),
                history.getChangedBy()
        );
    }
}
