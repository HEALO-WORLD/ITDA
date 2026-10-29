package healoworld.institution_service.infrastructure.jwt;

import healoworld.institution_service.domain.enums.InstitutionType;
import healoworld.institution_service.domain.enums.Role;

import java.util.UUID;

public record TokenClaims(UUID userId, UUID institutionId,
                          InstitutionType institutionType, Role role) {
}
