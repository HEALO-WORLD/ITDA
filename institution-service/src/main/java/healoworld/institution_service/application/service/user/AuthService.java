package healoworld.institution_service.application.service.user;

import healoworld.institution_service.domain.entity.Institution;
import healoworld.institution_service.domain.entity.User;
import healoworld.institution_service.global.exception.BaseException;
import healoworld.institution_service.global.exception.ErrorCode;
import healoworld.institution_service.infrastructure.jwt.JwtProvider;
import healoworld.institution_service.infrastructure.jwt.RefreshTokenClaims;
import healoworld.institution_service.infrastructure.redis.RefreshTokenRepository;
import healoworld.institution_service.infrastructure.redis.RotationResult;
import healoworld.institution_service.infrastructure.repository.UserRepository;
import healoworld.institution_service.presentation.dto.request.LoginRequest;
import healoworld.institution_service.presentation.dto.request.RefreshTokenRequest;
import healoworld.institution_service.presentation.dto.response.TokenResponse;
import io.jsonwebtoken.JwtException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuthService {
    private static final String GRACE_DELIMITER = " ";   // JWT에는 공백이 들어가지 않음

    private final JwtProvider jwtProvider;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenRepository refreshTokenRepository;

    public TokenResponse issueDevToken(DevTestAccount account) {
        String accessToken = jwtProvider.createAccessToken(
                account.getUserId(),
                account.getInstitutionId(),
                account.getInstitutionType(),
                account.getRole());
        return TokenResponse.bearer(accessToken, jwtProvider.getAccessTokenExpirationSeconds());
    }

    public TokenResponse login(LoginRequest request) {
        User user = userRepository.findByLoginIdAndDeletedAtIsNull(request.loginId())
                .orElseThrow(() -> new BaseException(ErrorCode.INVALID_CREDENTIALS));

        if (!passwordEncoder.matches(request.password(), user.getPassword())) {
            throw new BaseException(ErrorCode.INVALID_CREDENTIALS);
        }

        if (!user.isActive()) {
            throw new BaseException(ErrorCode.USER_NOT_APPROVED);
        }

        // 로그인마다 새 family 시작 → 기기별로 따로 관리됨
        UUID familyId = UUID.randomUUID();
        UUID tokenId = UUID.randomUUID();
        TokenResponse tokens = createTokens(user, familyId, tokenId);
        refreshTokenRepository.save(familyId, tokenId, jwtProvider.getRefreshTokenExpiration());
        return tokens;
    }

    public TokenResponse reissue(RefreshTokenRequest request) {
        // 1. 서명·만료·종류 검증
        RefreshTokenClaims claims = parseRefreshToken(request.refreshToken());

        // 2. 사용자 상태 재확인 (삭제·비활성이면 family도 폐기)
        User user = userRepository.findWithInstitutionByIdAndDeletedAtIsNull(claims.userId())
                .orElse(null);
        if (user == null || !user.isActive()) {
            refreshTokenRepository.delete(claims.familyId());
            throw new BaseException(ErrorCode.INVALID_REFRESH_TOKEN);
        }

        // 3. 새 토큰 쌍을 먼저 만들어 둠 (Lua 안에서 유예 키에 함께 저장해야 해서)
        UUID newTokenId = UUID.randomUUID();
        TokenResponse newTokens = createTokens(user, claims.familyId(), newTokenId);

        // 4. Redis에서 비교·교체를 한 번에
        RotationResult result = refreshTokenRepository.rotate(
                claims.familyId(), claims.tokenId(), newTokenId,
                jwtProvider.getRefreshTokenExpiration(),
                toGraceValue(newTokens),
                jwtProvider.getRefreshReuseGrace());

        return switch (result.status()) {
            case ROTATED -> newTokens;
            case GRACE -> fromGraceValue(result.graceValue());
            case REUSED -> throw new BaseException(ErrorCode.REFRESH_TOKEN_REUSED);
            case MISSING -> throw new BaseException(ErrorCode.INVALID_REFRESH_TOKEN);
        };
    }

    // 로그아웃은 항상 성공 (여러 번 호출해도 결과가 같고, 실패 이유를 알려줄 필요 없음)
    public void logout(RefreshTokenRequest request) {
        RefreshTokenClaims claims;
        try {
            claims = jwtProvider.parseRefreshToken(request.refreshToken());
        } catch (JwtException | IllegalArgumentException e) {
            return;   // 이미 만료됐거나 잘못된 토큰 → 지울 것이 없음
        }
        refreshTokenRepository.delete(claims.familyId());
    }

    private RefreshTokenClaims parseRefreshToken(String refreshToken) {
        try {
            return jwtProvider.parseRefreshToken(refreshToken);
        } catch (JwtException | IllegalArgumentException e) {
            throw new BaseException(ErrorCode.INVALID_REFRESH_TOKEN);
        }
    }

    private TokenResponse createTokens(User user, UUID familyId, UUID tokenId) {
        Institution institution = user.getInstitution();

        String accessToken = jwtProvider.createAccessToken(
                user.getId(),
                institution.getId(),
                institution.getType(),
                user.getRole());
        String refreshToken = jwtProvider.createRefreshToken(user.getId(), familyId, tokenId);

        return TokenResponse.bearer(accessToken, jwtProvider.getAccessTokenExpirationSeconds(),
                refreshToken, jwtProvider.getRefreshTokenExpiration().toSeconds());
    }

    private String toGraceValue(TokenResponse tokens) {
        return tokens.accessToken() + GRACE_DELIMITER + tokens.refreshToken();
    }

    private TokenResponse fromGraceValue(String graceValue) {
        String[] parts = graceValue.split(GRACE_DELIMITER);
        return TokenResponse.bearer(parts[0], jwtProvider.getAccessTokenExpirationSeconds(),
                parts[1], jwtProvider.getRefreshTokenExpiration().toSeconds());
    }
}
