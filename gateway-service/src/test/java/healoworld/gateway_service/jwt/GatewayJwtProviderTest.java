package healoworld.gateway_service.jwt;

import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.sql.Date;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class GatewayJwtProviderTest {
    private static final String TEST_SECRET = "dGVzdC1zZWNyZXQta2V5LWZvci1pdGRhLXRlc3RzLW9ubHk=";

    private GatewayJwtProvider jwtProvider;
    private SecretKey secretKey;

    @BeforeEach
    void setUp() {
        jwtProvider = new GatewayJwtProvider(new JwtProperties(TEST_SECRET));
        secretKey = Keys.hmacShaKeyFor(Decoders.BASE64.decode(TEST_SECRET));
    }

    @Test
    @DisplayName("Access Token이면 사용자 정보를 꺼낸다")
    void parse_accessToken() {
        String userId = UUID.randomUUID().toString();

        TokenClaims claims = jwtProvider.parse(token(userId, "ACCESS"));

        assertThat(claims.userId()).isEqualTo(userId);
        assertThat(claims.role()).isEqualTo("REQUESTER");
    }

    @Test
    @DisplayName("Refresh Token이면 JwtException (필터에서 401 INVALID_TOKEN)")
    void parse_refreshToken() {
        String refreshToken = token(UUID.randomUUID().toString(), "REFRESH");
        assertThatThrownBy(() -> jwtProvider.parse(refreshToken))
                .isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("tokenType이 없는 토큰이면 JwtException")
    void parse_withoutTokenType() {
        String token = token(UUID.randomUUID().toString(), null);
        assertThatThrownBy(() -> jwtProvider.parse(token))
                .isInstanceOf(JwtException.class);
    }

    // Institution이 발급하는 토큰과 같은 모양으로 만든다 (tokenType이 null이면 claim을 넣지 않음)
    private String token(String userId, String tokenType) {
        Instant now = Instant.now();
        var builder = Jwts.builder().subject(userId)
                .claim("institutionId", UUID.randomUUID().toString())
                .claim("institutionType", "REQUESTER")
                .claim("role", "REQUESTER")
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(3600)))
                .signWith(secretKey);
        if (tokenType != null) {
            builder.claim("tokenType", tokenType);
        }
        return builder.compact();
    }
}
