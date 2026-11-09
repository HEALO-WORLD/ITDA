package healoworld.institution_service.presentation.dto.response;

import healoworld.institution_service.domain.entity.Institution;
import healoworld.institution_service.domain.enums.InstitutionType;

import java.time.LocalDateTime;
import java.util.UUID;

public record InstitutionResponse(
        UUID id,
        String name,
        InstitutionType type,
        String address,
        String careInstitutionCode,
        boolean active,
        LocalDateTime createdAt
) {
    public static InstitutionResponse from(Institution institution) {
        return new InstitutionResponse(
                institution.getId(),
                institution.getName(),
                institution.getType(),
                institution.getAddress(),
                institution.getCareInstitutionCode(),
                institution.isActive(),
                institution.getCreatedAt()
        );
    }
}