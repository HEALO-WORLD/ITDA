package healoworld.slot_service.global.config;

import healoworld.slot_service.infrastructure.client.InstitutionClient;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableFeignClients(clients = InstitutionClient.class)
public class FeignConfig {
}