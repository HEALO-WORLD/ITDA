package healoworld.institution_service.global.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

// Swagger UI(/swagger-ui/index.html), OpenAPI 문서(/v3/api-docs) 기본 정보
@Configuration
public class OpenApiConfig {

    static final String GATEWAY_AUTH = "gatewayBearerAuth";

    private static final String DESCRIPTION = """
            Institution-service 정보를 관리하는 API입니다.
            """;

    @Bean
    public OpenAPI institutionServiceOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Institution Service API")
                        .description(DESCRIPTION)
                        .version("v1"))
                // 상대 경로: 문서를 연 주소로 호출한다
                // - Gateway 통합 Swagger UI(8080) 에서 열면 Gateway 경유 → Bearer JWT 필요, X-User-* 는 Gateway 가 JWT 값으로 덮어씀
                // - Company Service 자체 Swagger UI(8082) 에서 열면 직접 호출 → X-User-* 헤더 직접 입력
                .servers(List.of(
                        new Server().url("/").description("현재 Swagger UI 주소 기준 (Gateway 8080 이면 Gateway 경유, 8081 이면 직접 호출)")))
                .components(new Components()
                        .addSecuritySchemes(GATEWAY_AUTH, new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("Gateway 경유 시 사용하는 JWT. Institution Service 는 이 값을 직접 검증하지 않습니다.")));
    }
}
