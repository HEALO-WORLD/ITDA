package healoworld.institution_service.presentation.dto.request;

import healoworld.institution_service.domain.enums.EquipmentStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class EquipmentStatusUpdateRequest {
    @NotNull(message = "변경할 장비 상태는 필수입니다.")
    private EquipmentStatus status;

    @NotBlank(message = "상태 변경 사유는 필수입니다.")
    @Size(max = 500, message = "상태 변경 사유는 최대 500자여야 합니다.")
    private String reason;
}
