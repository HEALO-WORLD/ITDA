package healoworld.gateway_service.jwt;

public record TokenClaims(
        String userId,
        String institutionId,
        String institutionType,
        String role
) {
}
