package healoworld.slot_service.presentation.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.UUID;

// POST /api/v1/slots/{slotId}/release 요청
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SlotReleaseRequest {

    @Schema(description = "해제할 예약 ID. 슬롯이 이 예약으로 잡혀 있을 때만 해제한다", example = "3f1c2a9e-8b7d-4c6e-9a1f-2b3c4d5e6f70")
    @NotNull(message = "예약 ID(reservationId)는 필수입니다.")
    private UUID reservationId;
}
