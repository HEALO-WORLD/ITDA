package healoworld.institution_service.infrastructure.jwt;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "jwt")
public record JwtProperties(String secret,
                            Duration accessTokenExpiration,
                            Duration refreshTokenExpiration,
                            Duration refreshReuseGrace,
                            DevToken devToken
) {
    public record DevToken(boolean enabled) {
    }
}
