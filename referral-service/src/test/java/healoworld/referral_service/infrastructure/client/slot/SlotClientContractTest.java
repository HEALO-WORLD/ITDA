package healoworld.referral_service.infrastructure.client.slot;

import com.sun.net.httpserver.HttpServer;
import feign.FeignException;
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
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SlotClient ↔ Slot Service 내부 API(POST /api/v1/internal/slots/statuses) 계약 테스트.
 * <p>
 * - 실제 Spring Cloud OpenFeign 클라이언트와 실제 JSON 변환기로 HTTP 요청·응답을 주고받는다
 * - 상대 서버는 JDK 내장 HTTP 서버이며, Slot Service SlotInternalController가 내는 것과 같은 JSON 배열을 돌려준다
 *   (Slot 쪽 형식은 slot-service SlotInternalControllerTest가 같은 값으로 확인한다)
 * - DB·Kafka·Eureka 자동 설정을 불러오지 않는다 (Feign·JSON·메시지 변환기만). 주소는 Feign 클라이언트 설정으로 고정한다
 */
@SpringBootTest(classes = SlotClientContractTest.FeignOnlyConfig.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")   // 프로젝트 규칙(TestDatabaseGuard). DB 자동 설정을 불러오지 않아 실제로 DB에 연결하지 않는다
class SlotClientContractTest {

    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration({JacksonAutoConfiguration.class, HttpMessageConvertersAutoConfiguration.class,
            FeignAutoConfiguration.class})
    @EnableFeignClients(clients = SlotClient.class)
    static class FeignOnlyConfig {
    }

    private static final String PATH = "/api/v1/internal/slots/statuses";
    private static final HttpServer SERVER;
    private static final AtomicReference<String> RESPONSE_BODY = new AtomicReference<>("[]");
    private static final AtomicReference<Integer> RESPONSE_STATUS = new AtomicReference<>(200);
    private static final AtomicReference<String> LAST_METHOD = new AtomicReference<>();
    private static final AtomicReference<String> LAST_CONTENT_TYPE = new AtomicReference<>();
    private static final AtomicReference<String> LAST_REQUEST_BODY = new AtomicReference<>();

    static {
        try {
            SERVER = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        SERVER.createContext(PATH, exchange -> {
            LAST_METHOD.set(exchange.getRequestMethod());
            LAST_CONTENT_TYPE.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            LAST_REQUEST_BODY.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
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
    static void slotServiceUrl(DynamicPropertyRegistry registry) {
        registry.add("spring.cloud.openfeign.client.config.slot-service.url",
                () -> "http://127.0.0.1:" + SERVER.getAddress().getPort());
    }

    @AfterAll
    static void stopServer() {
        SERVER.stop(0);
    }

    @Autowired
    private SlotClient slotClient;

    @BeforeEach
    void reset() {
        RESPONSE_STATUS.set(200);
        RESPONSE_BODY.set("[]");
    }

    @Test
    @DisplayName("요청은 POST JSON {\"slotIds\":[...]}이고, Slot이 주는 배열을 slotId·status·reservationId·heldUntil로 읽는다")
    void exchangesStatusesWithSlotService() {
        UUID heldSlot = UUID.randomUUID();
        UUID heldReservation = UUID.randomUUID();
        UUID availableSlot = UUID.randomUUID();
        // SlotInternalController 응답과 같은 모양: ApiResponse로 감싸지 않은 배열, 시간대 없는 LocalDateTime, 없는 값은 null
        RESPONSE_BODY.set("""
                [{"slotId":"%s","status":"HELD","reservationId":"%s","heldUntil":"2026-11-13T10:05:00"},
                 {"slotId":"%s","status":"AVAILABLE","reservationId":null,"heldUntil":null}]
                """.formatted(heldSlot, heldReservation, availableSlot));

        List<SlotStatusResponse> result = slotClient.getSlotStatuses(new SlotStatusQueryRequest(List.of(heldSlot, availableSlot)));

        assertThat(LAST_METHOD.get()).isEqualTo("POST");
        assertThat(LAST_CONTENT_TYPE.get()).startsWith("application/json");
        assertThat(LAST_REQUEST_BODY.get()).isEqualTo("{\"slotIds\":[\"" + heldSlot + "\",\"" + availableSlot + "\"]}");
        assertThat(result).containsExactly(
                new SlotStatusResponse(heldSlot, "HELD", heldReservation, LocalDateTime.of(2026, 11, 13, 10, 5)),
                new SlotStatusResponse(availableSlot, "AVAILABLE", null, null));
        assertThat(result.getFirst().toSnapshot().isOwnedBy(heldReservation)).isTrue();
    }

    @Test
    @DisplayName("Slot이 오류로 응답하면 예외가 나므로 스케줄러는 그 회차를 건너뛴다 (API가 없을 때의 404 포함)")
    void errorResponseThrows() {
        RESPONSE_STATUS.set(404);
        RESPONSE_BODY.set("{\"code\":\"UNSUPPORTED_REQUEST\",\"message\":\"요청을 처리할 수 없습니다.\"}");

        assertThatThrownBy(() -> slotClient.getSlotStatuses(new SlotStatusQueryRequest(List.of(UUID.randomUUID()))))
                .isInstanceOf(FeignException.NotFound.class);
    }
}
