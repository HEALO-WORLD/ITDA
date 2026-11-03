package healoworld.institution_service.presentation.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class ExamTypeUpdateRequest {

    @Size(max = 50, message = "검사명은 최대 50자여야 합니다.")
    private String name;

    @Min(value = 1, message = "검사 소요시간은 1분 이상이어야 합니다.")
    private Integer durationMinutes;
}