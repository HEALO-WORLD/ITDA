package healoworld.institution_service.presentation.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record SignupRequest(
        @NotBlank @Size(min = 4, max = 50) String loginId,
        @NotBlank @Size(min = 8, max = 64) String password,
        @NotNull UUID institutionId
) {
}
