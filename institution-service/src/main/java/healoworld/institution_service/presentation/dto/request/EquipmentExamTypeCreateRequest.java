package healoworld.institution_service.presentation.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Getter
@NoArgsConstructor
public class EquipmentExamTypeCreateRequest {

    @NotNull(message = "검사 유형 ID는 필수입니다.")
    private UUID examTypeId;

    @NotNull(message = "검사 소요시간은 필수입니다.")
    @Min(value = 1, message = "검사 소요시간은 1분 이상이어야 합니다.")
    private Integer durationMinutes;
}
