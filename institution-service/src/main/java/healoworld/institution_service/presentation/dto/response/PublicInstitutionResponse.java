package healoworld.institution_service.presentation.dto.response;

import healoworld.institution_service.domain.entity.Institution;
import healoworld.institution_service.domain.enums.InstitutionType;

import java.util.UUID;

public record PublicInstitutionResponse(UUID id, String name, InstitutionType type) {

    public static PublicInstitutionResponse from(Institution institution) {
        return new PublicInstitutionResponse(institution.getId(), institution.getName(), institution.getType());
    }
}