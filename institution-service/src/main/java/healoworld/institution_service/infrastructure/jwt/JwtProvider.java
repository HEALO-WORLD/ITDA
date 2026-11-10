package healoworld.institution_service.infrastructure.jwt;

import healoworld.institution_service.domain.enums.InstitutionType;
import healoworld.institution_service.domain.enums.Role;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

@Component
public class JwtProvider {
    private static final String CLAIM_INSTITUTION_ID = "institutionId";
    private static final String CLAIM_INSTITUTION_TYPE = "institutionType";
    private static final String CLAIM_ROLE = "role";
    private static final String CLAIM_TOKEN_TYPE = "tokenType";
    private static final String CLAIM_FAMILY_ID = "familyId";
    private static final String TOKEN_TYPE_ACCESS = "ACCESS";
    private static final String TOKEN_TYPE_REFRESH = "REFRESH";

    private final SecretKey secretKey;
    private final Duration accessTokenExpiration;
    private final Duration refreshTokenExpiration;
    private final Duration refreshReuseGrace;

    public JwtProvider(JwtProperties properties) {
        this.secretKey = Keys.hmacShaKeyFor(Decoders.BASE64.decode(properties.secret()));
        this.accessTokenExpiration = properties.accessTokenExpiration();
        this.refreshTokenExpiration = properties.refreshTokenExpiration();
        this.refreshReuseGrace = properties.refreshReuseGrace();
    }

    // 토큰 만들기
    public String createAccessToken(UUID userId, UUID institutionId,
                                    InstitutionType institutionType, Role role) {
        Instant now = Instant.now();

        return Jwts.builder().subject(userId.toString())
                .claim(CLAIM_INSTITUTION_ID, institutionId.toString())
                .claim(CLAIM_INSTITUTION_TYPE, institutionType)
                .claim(CLAIM_ROLE, role.name())
                .claim(CLAIM_TOKEN_TYPE, TOKEN_TYPE_ACCESS)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(accessTokenExpiration)))
                .signWith(secretKey)
                .compact();
    }

    // refresh token: 사용자 정보는 넣지 않음 (재발급할 때 DB에서 최신 정보를 다시 읽음)
    public String createRefreshToken(UUID userId, UUID familyId, UUID tokenId) {
        Instant now = Instant.now();

        return Jwts.builder().subject(userId.toString())
                .id(tokenId.toString())                         // jti(JWT ID): 토큰마다 고유한 ID
                .claim(CLAIM_FAMILY_ID, familyId.toString())    // 같은 로그인에서 이어진 토큰 묶음
                .claim(CLAIM_TOKEN_TYPE, TOKEN_TYPE_REFRESH)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(refreshTokenExpiration)))
                .signWith(secretKey)
                .compact();
    }

    // 토큰 검증하고 값 꺼내기 (서명이 틀리거나 만료되면 JwtException 발생)
    public TokenClaims parse(String token) {
        Claims claims = parseClaims(token);

        return new TokenClaims(
                UUID.fromString(claims.getSubject()),
                UUID.fromString(claims.get(CLAIM_INSTITUTION_ID, String.class)),
                InstitutionType.valueOf(claims.get(CLAIM_INSTITUTION_TYPE, String.class)),
                Role.valueOf(claims.get(CLAIM_ROLE, String.class))
        );
    }

    // Refresh Token 검증: 서명·만료 + 종류가 REFRESH인지 + 필요한 값이 다 있는지
    public RefreshTokenClaims parseRefreshToken(String token) {
        Claims claims = parseClaims(token);
        if (!TOKEN_TYPE_REFRESH.equals(claims.get(CLAIM_TOKEN_TYPE, String.class))) {
            throw new MalformedJwtException("Refresh Token에 필요한 값이 없습니다.");
        }
        String familyId = claims.get(CLAIM_FAMILY_ID, String.class);
        if (claims.getSubject() == null || claims.getId() == null || familyId == null) {
            throw new MalformedJwtException("Refresh Token에 필요한 값이 없습니다.");
        }
        return new RefreshTokenClaims(UUID.fromString(claims.getSubject()), UUID.fromString(familyId), UUID.fromString(claims.getId()));
    }

    private Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public long getAccessTokenExpirationSeconds() {
        return accessTokenExpiration.toSeconds();
    }

    public Duration getRefreshTokenExpiration() {
        return refreshTokenExpiration;
    }

    public Duration getRefreshReuseGrace() {
        return refreshReuseGrace;
    }
}
