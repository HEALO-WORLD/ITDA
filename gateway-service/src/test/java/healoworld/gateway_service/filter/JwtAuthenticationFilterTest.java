package healoworld.gateway_service.filter;

import healoworld.gateway_service.jwt.GatewayJwtProvider;
import healoworld.gateway_service.jwt.JwtProperties;
import healoworld.gateway_service.policy.RolePolicy;
import healoworld.gateway_service.policy.RolePolicyProperties;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import javax.crypto.SecretKey;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JwtAuthenticationFilterTest {

    private static final String TEST_SECRET = "dGVzdC1zZWNyZXQta2V5LWZvci1pdGRhLXRlc3RzLW9ubHk=";
    private static final String OTHER_SECRET = "b3RoZXItc2VjcmV0LWtleS1mb3ItaXRkYS10ZXN0cy1vbmx5";

    private static final List<String> AUTH_HEADERS =
            List.of("X-User-Id", "X-Institution-Id", "X-Institution-Type", "X-Role");

    private final SecretKey secretKey = Keys.hmacShaKeyFor(Decoders.BASE64.decode(TEST_SECRET));
    private final SecretKey otherKey = Keys.hmacShaKeyFor(Decoders.BASE64.decode(OTHER_SECRET));

    private final String userId = UUID.randomUUID().toString();
    private final String institutionId = UUID.randomUUID().toString();

    private JwtAuthenticationFilter filter;

    // 필터를 통과하면 다음 체인으로 넘어간 exchange가 담긴다 (null이면 차단된 것)
    private ServerWebExchange passed;
    private final GatewayFilterChain chain = exchange -> {
        passed = exchange;
        return Mono.empty();
    };

    @BeforeEach
    void setUp() {
        JwtProperties jwtProperties = new JwtProperties(TEST_SECRET);
        // 실제 정책표 중 일부를 축소해 사용
        RolePolicy rolePolicy = new RolePolicy(new RolePolicyProperties(
                List.of("POST /api/v1/auth/login"),
                List.of(
                        new RolePolicyProperties.Group(List.of("REQUESTER"), List.of("POST /api/v1/partnerships")),
                        new RolePolicyProperties.Group(List.of("EXAMINER"), List.of("PATCH /api/v1/partnerships/*/approve"))
                )));
        filter = new JwtAuthenticationFilter(new GatewayJwtProvider(jwtProperties), rolePolicy);
        passed = null;
    }

    @Test
    @DisplayName("공개 API에 위조 X-헤더를 보내면 제거하고 통과시킨다")
    void publicApi_removesForgedHeaders() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/v1/auth/login")
                        .header("X-User-Id", "forged-user")
                        .header("X-Institution-Id", "forged-institution")
                        .header("X-Institution-Type", "EXAMINER")
                        .header("X-Role", "ADMIN"));

        filter.filter(exchange, chain).block();

        assertThat(passed).isNotNull();
        HttpHeaders headers = passed.getRequest().getHeaders();
        AUTH_HEADERS.forEach(name -> assertThat(headers.containsHeader(name)).as(name).isFalse());
    }

    @Test
    @DisplayName("인증 요청에 위조 X-헤더를 보내면 토큰 값으로 덮어쓴다")
    void authenticated_overwritesForgedHeaders() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/v1/partnerships")
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken("REQUESTER", validExpiry(), secretKey)))
                        .header("X-User-Id", "forged-user")
                        .header("X-Role", "ADMIN"));

        filter.filter(exchange, chain).block();

        assertThat(passed).isNotNull();
        HttpHeaders headers = passed.getRequest().getHeaders();
        assertThat(headers.get("X-User-Id")).containsExactly(userId);
        assertThat(headers.get("X-Institution-Id")).containsExactly(institutionId);
        assertThat(headers.get("X-Institution-Type")).containsExactly("REQUESTER");
        assertThat(headers.get("X-Role")).containsExactly("REQUESTER");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"Basic dXNlcjpwYXNzd29yZA==", "token-without-bearer"})
    @DisplayName("토큰이 없거나 Bearer 형식이 아니면 401 UNAUTHORIZED")
    void missingOrNotBearer_unauthorized(String authorization) {
        MockServerHttpRequest.BaseBuilder<?> request = MockServerHttpRequest.post("/api/v1/partnerships");
        if (authorization != null) {
            request.header(HttpHeaders.AUTHORIZATION, authorization);
        }
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        filter.filter(exchange, chain).block();

        assertRejected(exchange, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
    }

    @Test
    @DisplayName("만료된 토큰이면 401 TOKEN_EXPIRED")
    void expiredToken() {
        String expired = accessToken("REQUESTER", Instant.now().minusSeconds(60), secretKey);
        MockServerWebExchange exchange = exchangeWithToken("/api/v1/partnerships", expired);

        filter.filter(exchange, chain).block();

        assertRejected(exchange, HttpStatus.UNAUTHORIZED, "TOKEN_EXPIRED");
    }

    @Test
    @DisplayName("다른 키로 서명한 토큰이면 401 INVALID_TOKEN")
    void forgedSignature() {
        String forged = accessToken("ADMIN", validExpiry(), otherKey);
        MockServerWebExchange exchange = exchangeWithToken("/api/v1/partnerships", forged);

        filter.filter(exchange, chain).block();

        assertRejected(exchange, HttpStatus.UNAUTHORIZED, "INVALID_TOKEN");
    }

    @Test
    @DisplayName("역할이 맞지 않으면 403 ACCESS_DENIED")
    void roleNotAllowed() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.patch("/api/v1/partnerships/" + UUID.randomUUID() + "/approve")
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken("REQUESTER", validExpiry(), secretKey))));

        filter.filter(exchange, chain).block();

        assertRejected(exchange, HttpStatus.FORBIDDEN, "ACCESS_DENIED");
    }

    @Test
    @DisplayName("정책에 등록되지 않은 경로는 유효한 토큰이어도 403 ACCESS_DENIED (fail-closed)")
    void unregisteredPath() {
        MockServerWebExchange exchange = exchangeWithToken("/api/v1/unknown",
                accessToken("REQUESTER", validExpiry(), secretKey));

        filter.filter(exchange, chain).block();

        assertRejected(exchange, HttpStatus.FORBIDDEN, "ACCESS_DENIED");
    }

    private void assertRejected(MockServerWebExchange exchange, HttpStatus status, String code) {
        assertThat(passed).as("차단되면 다음 체인으로 넘어가지 않는다").isNull();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(status);
        assertThat(exchange.getResponse().getBodyAsString().block()).contains("\"code\":\"" + code + "\"");
    }

    private MockServerWebExchange exchangeWithToken(String path, String token) {
        return MockServerWebExchange.from(
                MockServerHttpRequest.post(path).header(HttpHeaders.AUTHORIZATION, bearer(token)));
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private Instant validExpiry() {
        return Instant.now().plusSeconds(3600);
    }

    // Institution이 발급하는 Access Token과 같은 모양
    private String accessToken(String role, Instant expiresAt, SecretKey key) {
        return Jwts.builder()
                .subject(userId)
                .claim("institutionId", institutionId)
                .claim("institutionType", role)
                .claim("role", role)
                .claim("tokenType", "ACCESS")
                .issuedAt(Date.from(expiresAt.minusSeconds(3600)))
                .expiration(Date.from(expiresAt))
                .signWith(key)
                .compact();
    }
}