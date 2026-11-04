package healoworld.institution_service.presentation.dto.request;

import healoworld.institution_service.domain.enums.InstitutionType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record InstitutionCreateRequest(
        @NotBlank @Size(max = 100) String name,
        @NotNull InstitutionType type,
        @NotBlank @Size(max = 255) String address,
        @NotBlank @Pattern(regexp = "\\d{8}", message = "요양기관기호는 숫자 8자리입니다.")
        String careInstitutionCode
) {
}
