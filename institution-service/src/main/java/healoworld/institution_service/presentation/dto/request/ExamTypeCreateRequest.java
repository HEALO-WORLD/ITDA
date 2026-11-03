package healoworld.institution_service.presentation.dto.request;

import healoworld.institution_service.domain.enums.Modality;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class ExamTypeCreateRequest {

    @NotBlank(message = "검사명은 필수입니다.")
    @Size(max = 50, message = "검사명은 최대 50자여야 합니다.")
    private String name;

    @NotNull(message = "검사 종류는 필수입니다.")
    private Modality modality;

    @NotNull(message = "검사 소요시간은 필수입니다.")
    @Min(value = 1, message = "검사 소요시간은 1분 이상이어야 합니다.")
    private Integer durationMinutes;
}