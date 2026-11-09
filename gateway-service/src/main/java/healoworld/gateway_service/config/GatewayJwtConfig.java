package healoworld.gateway_service.config;

import healoworld.gateway_service.jwt.JwtProperties;
import healoworld.gateway_service.policy.RolePolicyProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({JwtProperties.class, RolePolicyProperties.class})
public class GatewayJwtConfig {
}
