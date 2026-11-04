package healoworld.institution_service.presentation.dto.response;

import healoworld.institution_service.domain.entity.Partnership;
import healoworld.institution_service.domain.enums.PartnershipStatus;

import java.time.LocalDateTime;
import java.util.UUID;

public record PartnershipResponse(
        UUID id,
        UUID requesterInstitutionId,
        UUID examInstitutionId,
        PartnershipStatus status,
        LocalDateTime requestedAt,
        LocalDateTime approvedAt,
        LocalDateTime terminatedAt
) {
    public static PartnershipResponse from(Partnership partnership) {
        return new PartnershipResponse(
                partnership.getId(),
                partnership.getRequesterInstitution().getId(),
                partnership.getExamInstitution().getId(),
                partnership.getStatus(),
                partnership.getRequestedAt(),
                partnership.getApprovedAt(),
                partnership.getTerminatedAt()
        );
    }
}