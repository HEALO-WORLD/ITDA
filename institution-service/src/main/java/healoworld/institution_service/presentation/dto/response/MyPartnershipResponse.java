package healoworld.institution_service.presentation.dto.response;

import healoworld.institution_service.domain.entity.Institution;
import healoworld.institution_service.domain.entity.Partnership;
import healoworld.institution_service.domain.enums.InstitutionType;
import healoworld.institution_service.domain.enums.PartnershipStatus;

import java.time.LocalDateTime;
import java.util.UUID;

public record MyPartnershipResponse(
        UUID partnershipId,
        UUID partnerInstitutionId,
        String partnerInstitutionName,
        InstitutionType partnerInstitutionType,
        PartnershipStatus status,
        LocalDateTime requestedAt,
        LocalDateTime approvedAt,
        LocalDateTime terminatedAt
) {
    public static MyPartnershipResponse of(Partnership p, UUID myInstitutionId) {
        Institution partner = p.getPartnerOf(myInstitutionId);
        return new MyPartnershipResponse(
                p.getId(), partner.getId(), partner.getName(), partner.getType(),
                p.getStatus(), p.getRequestedAt(), p.getApprovedAt(), p.getTerminatedAt());
    }
}
