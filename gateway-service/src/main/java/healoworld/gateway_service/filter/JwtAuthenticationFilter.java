package healoworld.gateway_service.filter;

import healoworld.gateway_service.jwt.GatewayJwtProvider;
import healoworld.gateway_service.jwt.JwtProperties;
import healoworld.gateway_service.jwt.TokenClaims;
import healoworld.gateway_service.policy.RolePolicy;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import lombok.RequiredArgsConstructor;
import org.apache.http.HttpHeaders;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter implements GlobalFilter, Ordered {
    private static final String HEADER_USER_ID = "X-User-Id";
    private static final String HEADER_INSTITUTION_ID = "X-Institution-Id";
    private static final String HEADER_INSTITUTION_TYPE = "X-Institution-Type";
    private static final String HEADER_ROLE = "X-Role";
    private static final String BEARER_PREFIX = "Bearer ";
    private static final String BLOCK_INTERNAL_ROUTE_ID = "block-internal";
    private static final Set<String> SWAGGER_ROUTE_IDS = Set.of(
            "openapi-institution", "openapi-slot", "openapi-referral");

    @Value("${itda.swagger.enabled:false}")
    private boolean swaggerEnabled;

    private final GatewayJwtProvider jwtProvider;
    private final JwtProperties jwtProperties;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();
    private final RolePolicy rolePolicy;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {

        // 0. /internal 차단 라우트는 인증 없이 넘김 -> 라우트가 404로 응답(존재 자체를 숨김)
        Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        if (route != null && BLOCK_INTERNAL_ROUTE_ID.equals(route.getId())) {
            return chain.filter(exchange);
        }

        // 1. 바깥에서 보낸 인증 헤더 삭제 (위조 방지)
        ServerHttpRequest request = exchange.getRequest().mutate()
                .headers(headers -> {
                    headers.remove(HEADER_USER_ID);
                    headers.remove(HEADER_INSTITUTION_ID);
                    headers.remove(HEADER_INSTITUTION_TYPE);
                    headers.remove(HEADER_ROLE);
                })
                .build();

        // Swagger 문서 JSON은 로컬 Swagger UI에서 인증 없이 가져와야 한다.
        // 활성화된 경우에도 문서 전용 GET 라우트만 통과시킨다.
        if (swaggerEnabled && route != null && SWAGGER_ROUTE_IDS.contains(route.getId())) {
            return chain.filter(exchange.mutate().request(request).build());
        }

        // 2. 공개 API면 그대로 통과
        String path = request.getURI().getPath();
        if (rolePolicy.isPublic(request.getMethod(), path)) {
            return chain.filter(exchange.mutate().request(request).build());
        }

        // 3. Authorization 헤더에서 토큰 꺼내기
        String authorization = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
            return unauthorized(exchange, "UNAUTHORIZED", "인증 토큰이 필요합니다.");
        }
        String token = authorization.substring(BEARER_PREFIX.length());

        // 4. 서명, 만료 검증
        TokenClaims claims;
        try {
            claims = jwtProvider.parse(token);
        } catch (ExpiredJwtException e) {
            return unauthorized(exchange, "TOKEN_EXPIRED", "토큰이 만료되었습니다.");
        } catch (JwtException | IllegalArgumentException e) {
            return unauthorized(exchange, "INVALID_TOKEN", "유효하지 않은 토큰입니다.");
        }

        // 4-1. 역할 검증
        if (!rolePolicy.isAllowed(request.getMethod(), path, claims.role())) {
            return errorResponse(exchange, HttpStatus.FORBIDDEN, "ACCESS_DENIED", "접근 권한이 없습니다.");
        }

        // 5. 토큰 안에 값으로 헤더 4개 붙이기
        ServerHttpRequest authenticated = request.mutate()
                .header(HEADER_USER_ID, claims.userId())
                .header(HEADER_INSTITUTION_ID, claims.institutionId())
                .header(HEADER_INSTITUTION_TYPE, claims.institutionType())
                .header(HEADER_ROLE, claims.role()).build();

        // 6. 다음 서비스로 전달
        return chain.filter(exchange.mutate().request(authenticated).build());
    }

    private Mono<Void> errorResponse(ServerWebExchange exchange, HttpStatus status, String code, String message) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);

        String body = "{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}";
        DataBuffer buffer = response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }

    private Mono<Void> unauthorized(ServerWebExchange exchange, String code, String message) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);

        String body = "{\"code\":\"" + code + "\",\"message\" :\"" + message + "\" }";
        DataBuffer buffer = response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }

    private boolean isPublicPath(String path) {
        return jwtProperties.publicPaths().stream()
                .anyMatch(pattern -> pathMatcher.match(pattern, path));
    }

    /**
     * 숫자가 작을수록 먼저 실행. 다른 필터보다 먼저 인증을 끝내야해서 앞쪽에.
     */
    @Override
    public int getOrder() {
        return -1;
    }

}
