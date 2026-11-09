package healoworld.institution_service.presentation.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class ExamTypeUpdateRequest {

    @NotBlank(message = "검사명은 필수입니다.")
    @Size(max = 50, message = "검사명은 최대 50자여야 합니다.")
    private String name;

}
