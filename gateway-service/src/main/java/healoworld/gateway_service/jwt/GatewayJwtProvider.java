package healoworld.gateway_service.jwt;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;

@Component
public class GatewayJwtProvider {
    private static final String CLAIM_INSTITUTION_ID = "institutionId";
    private static final String CLAIM_INSTITUTION_TYPE = "institutionType";
    private static final String CLAIM_ROLE = "role";
    private static final String CLAIM_TOKEN_TYPE = "tokenType";
    private static final String TOKEN_TYPE_ACCESS = "ACCESS";

    private final SecretKey secretKey;

    public GatewayJwtProvider(JwtProperties jwtProperties) {
        this.secretKey = Keys.hmacShaKeyFor(Decoders.BASE64.decode(jwtProperties.secret()));
    }

    public TokenClaims parse(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();

        // Refresh Token(또는 종류 표시가 없는 토큰)으로 API를 호출하지 못하게 막음
        if (!TOKEN_TYPE_ACCESS.equals(claims.get(CLAIM_TOKEN_TYPE, String.class))) {
            throw new MalformedJwtException("Access Token이 아닙니다.");
        }

        return new TokenClaims(
                claims.getSubject(),
                claims.get(CLAIM_INSTITUTION_ID, String.class),
                claims.get(CLAIM_INSTITUTION_TYPE, String.class),
                claims.get(CLAIM_ROLE, String.class)
        );
    }


}
