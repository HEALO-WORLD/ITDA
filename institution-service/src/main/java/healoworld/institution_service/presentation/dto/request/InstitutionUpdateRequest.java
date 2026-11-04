package healoworld.institution_service.presentation.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// 수정 가능한 필드는 이름·주소만 (요양기관기호·유형은 변경 불가)
public record InstitutionUpdateRequest(
        @NotBlank @Size(max = 100) String name,
        @NotBlank @Size(max = 255) String address
) {
}