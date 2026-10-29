package healoworld.institution_service.application.service.auth;

import healoworld.institution_service.infrastructure.jwt.JwtProvider;
import healoworld.institution_service.presentation.dto.response.TokenResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuthService {
    private final JwtProvider jwtProvider;

    public TokenResponse issueDevToken(DevTestAccount account) {
        String accessToken = jwtProvider.createAccessToken(
                account.getUserId(),
                account.getInstitutionId(),
                account.getInstitutionType(),
                account.getRole());
        return TokenResponse.bearer(accessToken, jwtProvider.getAccessTokenExpirationSeconds());
    }
}
