package healoworld.slot_service.presentation.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// GET /api/v1/slots/{slotId}/status-histories 페이징 파라미터
@Getter
@Setter
@NoArgsConstructor
public class SlotStatusHistorySearchRequest {

    @NotNull(message = "페이지 번호는 필수입니다.")
    @Min(value = 0, message = "페이지 번호는 0 이상이어야 합니다.")
    private Integer page = 0;

    @NotNull(message = "페이지 크기는 필수입니다.")
    @Min(value = 1, message = "페이지 크기는 1 이상이어야 합니다.")
    @Max(value = 100, message = "페이지 크기는 100 이하여야 합니다.")
    private Integer size = 20;
}
