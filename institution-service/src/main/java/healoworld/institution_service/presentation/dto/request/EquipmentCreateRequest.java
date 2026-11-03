package healoworld.institution_service.presentation.dto.request;

import healoworld.institution_service.domain.enums.Modality;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class EquipmentCreateRequest {

    @NotBlank(message = "장비명은 필수입니다.")
    @Size(max = 50, message = "장비명은 최대 50자여야 합니다.")
    private String name;

    @NotNull(message = "장비 종류는 필수입니다.")
    private Modality modality;
}