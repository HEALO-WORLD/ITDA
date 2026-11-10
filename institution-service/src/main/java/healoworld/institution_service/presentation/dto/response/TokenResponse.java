package healoworld.institution_service.presentation.dto.response;

public record TokenResponse(String accessToken, String tokenType, long expiresIn,
                            String refreshToken, Long refreshExpiresIn) {

    // 개발용 토큰 (refresh 없음)
    public static TokenResponse bearer(String accessToken, long expiresIn) {
        return new TokenResponse(accessToken, "Bearer", expiresIn, null, null);
    }

    public static TokenResponse bearer(String accessToken, long expiresIn,
                                       String refreshToken, long refreshExpiresIn) {
        return new TokenResponse(accessToken, "Bearer", expiresIn, refreshToken, refreshExpiresIn);
    }
}
