package healoworld.gateway_service.jwt;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.List;

@Component
public class GatewayJwtProvider {
    private static final String CLAIM_INSTITUTION_ID = "institutionId";
    private static final String CLAIM_INSTITUTION_TYPE = "institutionType";
    private static final String CLAIM_ROLE = "role";

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

        return new TokenClaims(
                claims.getSubject(),
                claims.get(CLAIM_INSTITUTION_ID, String.class),
                claims.get(CLAIM_INSTITUTION_TYPE, String.class),
                claims.get(CLAIM_ROLE, String.class)
        );
    }


}
