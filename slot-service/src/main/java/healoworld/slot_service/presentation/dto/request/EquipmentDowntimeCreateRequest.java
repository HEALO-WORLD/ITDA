package healoworld.slot_service.presentation.dto.request;

import healoworld.slot_service.domain.entity.EquipmentDowntime;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.UUID;

// 시간은 슬롯 등록 API와 같이 오프셋을 포함한 일시로 받고, 서비스에서 한국 시간으로 변환해 저장한다
@Getter
@NoArgsConstructor
public class EquipmentDowntimeCreateRequest {

    @Schema(description = "중단할 장비 ID")
    @NotNull(message = "장비 ID는 필수입니다.")
    private UUID equipmentId;

    @Schema(description = "중단 시작 일시", example = "2026-11-10T09:00:00+09:00")
    @NotNull(message = "중단 시작 일시는 필수입니다.")
    private OffsetDateTime startedAt;

    @Schema(description = "중단 종료 일시 (시작 일시 이후)", example = "2026-11-10T18:00:00+09:00")
    @NotNull(message = "중단 종료 일시는 필수입니다.")
    private OffsetDateTime endedAt;

    // Referral의 EquipmentDowntimeStarted Consumer가 reason을 필수로 검증하므로 여기서도 필수로 받는다
    @Schema(description = "중단 사유 (최대 500자)", example = "갠트리 회전부 고장")
    @NotBlank(message = "중단 사유는 필수입니다.")
    @Size(max = EquipmentDowntime.REASON_MAX_LENGTH, message = "중단 사유는 500자 이하여야 합니다.")
    private String reason;
}
