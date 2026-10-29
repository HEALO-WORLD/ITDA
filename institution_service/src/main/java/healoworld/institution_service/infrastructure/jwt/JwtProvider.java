package healoworld.institution_service.infrastructure.jwt;

import healoworld.institution_service.domain.enums.InstitutionType;
import healoworld.institution_service.domain.enums.Role;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
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

    private final SecretKey secretKey;
    private final Duration accessTokenExpiration;

    public JwtProvider(JwtProperties properties) {
        this.secretKey = Keys.hmacShaKeyFor(Decoders.BASE64.decode(properties.secret()));
        this.accessTokenExpiration = properties.accessTokenExpiration();
    }

    // 토큰 만들기
    public String createAccessToken(UUID userId, UUID institutionId,
                                    InstitutionType institutionType, Role role) {
        Instant now = Instant.now();

        return Jwts.builder().subject(userId.toString())
                .claim(CLAIM_INSTITUTION_ID, institutionId.toString())
                .claim(CLAIM_INSTITUTION_TYPE, institutionType)
                .claim(CLAIM_ROLE, role.name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(accessTokenExpiration)))
                .signWith(secretKey)
                .compact();
    }

    // 토큰 검증하고 값 꺼내기 (서명이 틀리거나 만료되면 JwtException 발생)
    public TokenClaims parse(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();

        return new TokenClaims(
                UUID.fromString(claims.getSubject()),
                UUID.fromString(claims.get(CLAIM_INSTITUTION_ID, String.class)),
                InstitutionType.valueOf(claims.get(CLAIM_INSTITUTION_TYPE, String.class)),
                Role.valueOf(claims.get(CLAIM_ROLE, String.class))
        );
    }

    public long getAccessTokenExpirationSeconds() {
        return accessTokenExpiration.toSeconds();
    }
}
