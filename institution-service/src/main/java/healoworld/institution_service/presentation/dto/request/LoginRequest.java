package healoworld.institution_service.presentation.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LoginRequest(@NotBlank String loginId,
                           @NotBlank String password) {
}
