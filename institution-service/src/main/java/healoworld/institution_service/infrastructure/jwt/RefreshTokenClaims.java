package healoworld.institution_service.infrastructure.jwt;

import java.util.UUID;

public record RefreshTokenClaims(UUID userId, UUID familyId, UUID tokenId) {
}
