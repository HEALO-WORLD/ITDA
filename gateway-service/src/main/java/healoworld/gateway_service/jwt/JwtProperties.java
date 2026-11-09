package healoworld.gateway_service.jwt;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "jwt")
public record JwtProperties(String secret, List<String> publicPaths) {
}
