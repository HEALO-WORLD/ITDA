package healoworld.slot_service.presentation.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

// POST /api/v1/internal/slots/statuses 요청 (Referral SlotStatusQueryRequest와 같은 모양: {"slotIds": [...]})
public record SlotStatusQueryRequest(
        @Schema(description = "상태를 조회할 슬롯 ID 목록. 중복은 한 번만 조회한다")
        @NotNull(message = "슬롯 ID 목록(slotIds)은 필수입니다.")
        @Size(max = SlotStatusQueryRequest.MAX_SLOT_IDS, message = "슬롯 ID는 한 번에 1000개까지 조회할 수 있습니다.")
        List<@NotNull(message = "슬롯 ID는 비어 있을 수 없습니다.") UUID> slotIds
) {
    // Referral 정합성 점검은 회차당 대상 조회 3종 × batch-size(기본 50)만큼 보낸다. 그보다 넉넉하되 무제한은 막는다
    public static final int MAX_SLOT_IDS = 1000;
}
