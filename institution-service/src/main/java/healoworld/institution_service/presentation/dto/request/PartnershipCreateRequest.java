package healoworld.institution_service.presentation.dto.request;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record PartnershipCreateRequest(
        @NotNull UUID examInstitutionId
) {
}