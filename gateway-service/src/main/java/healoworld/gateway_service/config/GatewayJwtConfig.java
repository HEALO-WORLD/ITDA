package healoworld.gateway_service.config;

import healoworld.gateway_service.jwt.JwtProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(JwtProperties.class)
public class GatewayJwtConfig {
}
