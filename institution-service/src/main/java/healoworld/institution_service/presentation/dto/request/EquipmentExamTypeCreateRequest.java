package healoworld.institution_service.presentation.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Getter
@NoArgsConstructor
public class EquipmentExamTypeCreateRequest {

    @NotNull(message = "검사 유형 ID는 필수입니다.")
    private UUID examTypeId;
}