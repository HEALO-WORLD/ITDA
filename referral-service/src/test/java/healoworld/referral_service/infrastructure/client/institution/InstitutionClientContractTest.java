package healoworld.referral_service.infrastructure.client.institution;

import com.sun.net.httpserver.HttpServer;
import feign.FeignException;
import healoworld.referral_service.global.dto.ApiResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.cloud.openfeign.FeignAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * InstitutionClient ↔ Institution Service 내부 API 계약 테스트.
 * <p>
 * - GET /api/v1/internal/partnerships/check (PartnershipInternalController): ApiResponse로 감싸지 않은 {"active": boolean}
 * - GET /api/v1/internal/equipments/{equipmentId}/owner (EquipmentInternalController): ApiResponse로 감싼 {equipmentId, institutionId},
 *   장비가 없으면 404 RESOURCE_NOT_FOUND
 * - 실제 Spring Cloud OpenFeign 클라이언트와 실제 JSON 변환기로 주고받고, 상대 서버는 JDK 내장 HTTP 서버다 (SlotClientContractTest와 같은 구성)
 * - ReferralService는 404를 "장비 없음(400 EQUIPMENT_NOT_IN_EXAM_INSTITUTION)", 그 밖의 오류를 503으로 바꾸므로
 *   Feign이 상태 코드별로 어떤 예외를 던지는지도 여기서 고정한다
 */
@SpringBootTest(classes = InstitutionClientContractTest.FeignOnlyConfig.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")   // 프로젝트 규칙(TestDatabaseGuard). DB 자동 설정을 불러오지 않아 실제로 DB에 연결하지 않는다
class InstitutionClientContractTest {

    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration({JacksonAutoConfiguration.class, HttpMessageConvertersAutoConfiguration.class,
            FeignAutoConfiguration.class})
    @EnableFeignClients(clients = InstitutionClient.class)
    static class FeignOnlyConfig {
    }

    private static final HttpServer SERVER;
    private static final AtomicReference<String> RESPONSE_BODY = new AtomicReference<>("{}");
    private static final AtomicReference<Integer> RESPONSE_STATUS = new AtomicReference<>(200);
    private static final AtomicReference<String> LAST_METHOD = new AtomicReference<>();
    private static final AtomicReference<String> LAST_PATH = new AtomicReference<>();
    private static final AtomicReference<String> LAST_QUERY = new AtomicReference<>();

    static {
        try {
            SERVER = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        SERVER.createContext("/api/v1/internal/", exchange -> {
            LAST_METHOD.set(exchange.getRequestMethod());
            LAST_PATH.set(exchange.getRequestURI().getPath());
            LAST_QUERY.set(exchange.getRequestURI().getQuery());
            byte[] body = RESPONSE_BODY.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(RESPONSE_STATUS.get(), body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        SERVER.start();
    }

    @DynamicPropertySource
    static void institutionServiceUrl(DynamicPropertyRegistry registry) {
        registry.add("spring.cloud.openfeign.client.config.institution-service.url",
                () -> "http://127.0.0.1:" + SERVER.getAddress().getPort());
    }

    @AfterAll
    static void stopServer() {
        SERVER.stop(0);
    }

    @Autowired
    private InstitutionClient institutionClient;

    private final UUID requesterInstitutionId = UUID.randomUUID();
    private final UUID examInstitutionId = UUID.randomUUID();
    private final UUID equipmentId = UUID.randomUUID();

    @BeforeEach
    void reset() {
        RESPONSE_STATUS.set(200);
        RESPONSE_BODY.set("{}");
    }

    // ---------- 협약 확인 ----------

    @Test
    @DisplayName("협약 확인은 GET /partnerships/check?requesterInstitutionId&examInstitutionId이고, 감싸지 않은 {\"active\":true}를 읽는다")
    void checkActivePartnership() {
        RESPONSE_BODY.set("{\"active\":true}");

        PartnershipCheckResponse response = institutionClient.check(requesterInstitutionId, examInstitutionId);

        assertThat(response.active()).isTrue();
        assertThat(LAST_METHOD.get()).isEqualTo("GET");
        assertThat(LAST_PATH.get()).isEqualTo("/api/v1/internal/partnerships/check");
        assertThat(LAST_QUERY.get()).contains("requesterInstitutionId=" + requesterInstitutionId)
                .contains("examInstitutionId=" + examInstitutionId);
    }

    @Test
    @DisplayName("협약이 없으면 {\"active\":false}를 false로 읽는다 (예외가 아니다)")
    void checkInactivePartnership() {
        RESPONSE_BODY.set("{\"active\":false}");

        assertThat(institutionClient.check(requesterInstitutionId, examInstitutionId).active()).isFalse();
    }

    @Test
    @DisplayName("협약 확인이 5xx로 실패하면 FeignException이 나므로 ReferralService가 503으로 바꾼다")
    void checkServerError() {
        RESPONSE_STATUS.set(500);
        RESPONSE_BODY.set("{\"code\":\"INTERNAL_SERVER_ERROR\",\"message\":\"서버 내부 오류가 발생했습니다.\"}");

        assertThatThrownBy(() -> institutionClient.check(requesterInstitutionId, examInstitutionId))
                .isInstanceOf(FeignException.class)
                .isNotInstanceOf(FeignException.NotFound.class);
    }

    // ---------- 장비 소유 기관 ----------

    @Test
    @DisplayName("장비 소유 조회는 GET /equipments/{equipmentId}/owner이고, ApiResponse로 감싼 data.institutionId를 읽는다")
    void getEquipmentOwner() {
        // EquipmentInternalController 응답과 같은 모양: ApiResponse(success·message·data·timestamp)
        RESPONSE_BODY.set("""
                {"success":true,"message":"장비 소속 기관 조회에 성공했습니다.",
                 "data":{"equipmentId":"%s","institutionId":"%s"},
                 "timestamp":"2026-11-11T09:00:00.123456"}
                """.formatted(equipmentId, examInstitutionId));

        ApiResponse<EquipmentOwnerResponse> response = institutionClient.getEquipmentOwner(equipmentId);

        assertThat(LAST_METHOD.get()).isEqualTo("GET");
        assertThat(LAST_PATH.get()).isEqualTo("/api/v1/internal/equipments/" + equipmentId + "/owner");
        assertThat(response.getSuccess()).isTrue();
        assertThat(response.getData()).isEqualTo(new EquipmentOwnerResponse(equipmentId, examInstitutionId));
    }

    @Test
    @DisplayName("장비가 없으면 404 → FeignException.NotFound이므로 ReferralService가 400 EQUIPMENT_NOT_IN_EXAM_INSTITUTION으로 바꾼다")
    void equipmentNotFound() {
        RESPONSE_STATUS.set(404);
        RESPONSE_BODY.set("{\"code\":\"RESOURCE_NOT_FOUND\",\"message\":\"리소스를 찾을 수 없습니다.\"}");

        assertThatThrownBy(() -> institutionClient.getEquipmentOwner(equipmentId))
                .isInstanceOf(FeignException.NotFound.class);
    }

    @Test
    @DisplayName("장비 소유 조회가 503이면 NotFound가 아닌 FeignException이므로 ReferralService가 503으로 바꾼다")
    void equipmentServiceUnavailable() {
        RESPONSE_STATUS.set(503);
        RESPONSE_BODY.set("{\"code\":\"SERVICE_UNAVAILABLE\",\"message\":\"잠시 후 다시 시도해 주세요.\"}");

        assertThatThrownBy(() -> institutionClient.getEquipmentOwner(equipmentId))
                .isInstanceOf(FeignException.class)
                .isNotInstanceOf(FeignException.NotFound.class);
    }
}
