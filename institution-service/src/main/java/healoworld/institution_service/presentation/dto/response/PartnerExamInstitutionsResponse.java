package healoworld.institution_service.presentation.dto.response;

import java.util.List;
import java.util.UUID;

public record PartnerExamInstitutionsResponse(List<UUID> examInstitutionIds) {
}
