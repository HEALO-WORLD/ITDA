package healoworld.gateway_service.policy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpMethod;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RolePolicyTest {

    // 실제 정책표 중 일부를 축소해 사용
    private final RolePolicy policy = new RolePolicy(new RolePolicyProperties(
            List.of("POST /api/v1/auth/login", "GET /api/v1/institutions/public"),
            List.of(
                    group(List.of("ADMIN"), "GET,POST /api/v1/institutions", "PATCH /api/v1/users/*/approve"),
                    group(List.of("REQUESTER"), "POST /api/v1/partnerships"),
                    group(List.of("EXAMINER"), "PATCH /api/v1/partnerships/*/approve"),
                    group(List.of("REQUESTER", "EXAMINER", "ADMIN"), "GET /api/v1/institutions/*")
            )));

    @Nested
    @DisplayName("공개 API")
    class Public {

        @Test
        @DisplayName("등록된 Method + Path만 공개다")
        void isPublic() {
            assertThat(policy.isPublic(HttpMethod.POST, "/api/v1/auth/login")).isTrue();
            assertThat(policy.isPublic(HttpMethod.GET, "/api/v1/auth/login")).isFalse();   // Method가 다름
            assertThat(policy.isPublic(HttpMethod.GET, "/api/v1/users/me")).isFalse();
        }
    }

    @Nested
    @DisplayName("역할 허용")
    class Allowed {

        @Test
        @DisplayName("규칙의 역할에 포함되면 허용, 아니면 거부")
        void allowedByRole() {
            assertThat(policy.isAllowed(HttpMethod.POST, "/api/v1/partnerships", "REQUESTER")).isTrue();
            assertThat(policy.isAllowed(HttpMethod.POST, "/api/v1/partnerships", "EXAMINER")).isFalse();
            assertThat(policy.isAllowed(HttpMethod.PATCH, "/api/v1/partnerships/abc/approve", "EXAMINER")).isTrue();
            assertThat(policy.isAllowed(HttpMethod.PATCH, "/api/v1/partnerships/abc/approve", "REQUESTER")).isFalse();
        }

        @Test
        @DisplayName("Method를 쉼표로 묶은 규칙은 각각 적용된다")
        void multipleMethods() {
            assertThat(policy.isAllowed(HttpMethod.GET, "/api/v1/institutions", "ADMIN")).isTrue();
            assertThat(policy.isAllowed(HttpMethod.POST, "/api/v1/institutions", "ADMIN")).isTrue();
            assertThat(policy.isAllowed(HttpMethod.GET, "/api/v1/institutions", "REQUESTER")).isFalse();
        }

        @Test
        @DisplayName("*는 경로 한 칸만 매칭한다")
        void singleSegmentWildcard() {
            assertThat(policy.isAllowed(HttpMethod.GET, "/api/v1/institutions/abc", "REQUESTER")).isTrue();
            assertThat(policy.isAllowed(HttpMethod.GET, "/api/v1/institutions/abc/def", "ADMIN")).isFalse();
        }

        @Test
        @DisplayName("정책표에 없는 요청은 어떤 역할이든 거부한다 (fail-closed)")
        void failClosed() {
            assertThat(policy.isAllowed(HttpMethod.DELETE, "/api/v1/partnerships", "ADMIN")).isFalse();
            assertThat(policy.isAllowed(HttpMethod.GET, "/api/v1/unknown", "ADMIN")).isFalse();
        }

        @Test
        @DisplayName("여러 규칙이 맞으면 가장 구체적인 규칙을 따른다")
        void mostSpecificWins() {
            RolePolicy specific = new RolePolicy(new RolePolicyProperties(List.of(), List.of(
                    group(List.of("REQUESTER", "EXAMINER", "ADMIN"), "GET /api/v1/institutions/*"),
                    group(List.of("ADMIN"), "GET /api/v1/institutions/public")
            )));

            // /public은 두 규칙에 다 맞지만, 와일드카드 없는 규칙이 더 구체적이라 ADMIN만 허용
            assertThat(specific.isAllowed(HttpMethod.GET, "/api/v1/institutions/public", "REQUESTER")).isFalse();
            assertThat(specific.isAllowed(HttpMethod.GET, "/api/v1/institutions/public", "ADMIN")).isTrue();
            assertThat(specific.isAllowed(HttpMethod.GET, "/api/v1/institutions/abc", "REQUESTER")).isTrue();
        }
    }

    @Nested
    @DisplayName("잘못된 정책표는 서버 시작을 막는다")
    class InvalidPolicy {

        @Test
        @DisplayName("같은 Method + Path가 두 번 있으면 예외")
        void duplicate() {
            assertThatThrownBy(() -> new RolePolicy(new RolePolicyProperties(List.of(), List.of(
                    group(List.of("REQUESTER"), "POST /api/v1/partnerships"),
                    group(List.of("EXAMINER"), "POST /api/v1/partnerships")
            )))).isInstanceOf(IllegalStateException.class).hasMessageContaining("중복");
        }

        @Test
        @DisplayName("공개 API와 보호 API가 겹치면 예외")
        void publicAndProtected() {
            assertThatThrownBy(() -> new RolePolicy(new RolePolicyProperties(
                    List.of("POST /api/v1/auth/login"),
                    List.of(group(List.of("ADMIN"), "POST /api/v1/auth/login"))
            ))).isInstanceOf(IllegalStateException.class).hasMessageContaining("중복");
        }

        @Test
        @DisplayName("알 수 없는 역할이면 예외")
        void unknownRole() {
            assertThatThrownBy(() -> new RolePolicy(new RolePolicyProperties(List.of(), List.of(
                    group(List.of("DOCTOR"), "GET /api/v1/users/me")
            )))).isInstanceOf(IllegalStateException.class).hasMessageContaining("알 수 없는 역할");
        }

        @Test
        @DisplayName("알 수 없는 Method면 예외")
        void unknownMethod() {
            assertThatThrownBy(() -> new RolePolicy(new RolePolicyProperties(List.of(), List.of(
                    group(List.of("ADMIN"), "FETCH /api/v1/users")
            )))).isInstanceOf(IllegalStateException.class).hasMessageContaining("알 수 없는 메서드");
        }

        @Test
        @DisplayName("**를 쓰면 예외 (범위가 너무 넓음)")
        void doubleWildcard() {
            assertThatThrownBy(() -> new RolePolicy(new RolePolicyProperties(List.of(), List.of(
                    group(List.of("ADMIN"), "GET /api/v1/**")
            )))).isInstanceOf(IllegalStateException.class).hasMessageContaining("**");
        }

        @Test
        @DisplayName("Method와 Path 형식이 아니면 예외")
        void invalidFormat() {
            assertThatThrownBy(() -> new RolePolicy(new RolePolicyProperties(List.of(), List.of(
                    group(List.of("ADMIN"), "/api/v1/users")
            )))).isInstanceOf(IllegalStateException.class).hasMessageContaining("잘못된 정책 형식");
        }
    }

    // 축소본이 아니라 실제 application.yml의 gateway.policy로 만든 정책 (Spring 컨텍스트 없이 바인딩만 한다)
    @Nested
    @DisplayName("실제 정책표 (application.yml)")
    class ActualPolicy {

        private static final String RESERVATION = "/api/v1/referrals/reservations/71de0c4e-1b2a-4c3d-9e8f-0a1b2c3d4e5f";

        private final RolePolicy actual = loadActualPolicy();

        @Test
        @DisplayName("예약 삭제는 제공하지 않으므로 모든 역할을 거부한다")
        void deleteReservationDeniedForAllRoles() {
            for (String role : List.of("REQUESTER", "EXAMINER", "ADMIN")) {
                assertThat(actual.isAllowed(HttpMethod.DELETE, RESERVATION, role)).as(role).isFalse();
            }
        }

        @Test
        @DisplayName("예약 취소 권한은 그대로 REQUESTER만 허용한다")
        void cancelReservationUnchanged() {
            assertThat(actual.isAllowed(HttpMethod.PATCH, RESERVATION + "/cancel", "REQUESTER")).isTrue();
            assertThat(actual.isAllowed(HttpMethod.PATCH, RESERVATION + "/cancel", "EXAMINER")).isFalse();
            assertThat(actual.isAllowed(HttpMethod.PATCH, RESERVATION + "/cancel", "ADMIN")).isFalse();
        }

        @Test
        @DisplayName("의뢰 삭제·예약 조회 권한은 바뀌지 않는다")
        void otherReservationRulesUnchanged() {
            String referral = "/api/v1/referrals/71de0c4e-1b2a-4c3d-9e8f-0a1b2c3d4e5f";
            assertThat(actual.isAllowed(HttpMethod.DELETE, referral, "REQUESTER")).isTrue();
            assertThat(actual.isAllowed(HttpMethod.DELETE, referral, "ADMIN")).isTrue();
            assertThat(actual.isAllowed(HttpMethod.DELETE, referral, "EXAMINER")).isFalse();
            for (String role : List.of("REQUESTER", "EXAMINER", "ADMIN")) {
                assertThat(actual.isAllowed(HttpMethod.GET, RESERVATION, role)).as(role).isTrue();
            }
        }

        @Test
        @DisplayName("장비 장애 현황(summary)은 EXAMINER·ADMIN만 허용하고 REQUESTER는 거부한다")
        void incidentSummaryForExaminerAndAdmin() {
            String summary = "/api/v1/equipment-incidents/summary";
            assertThat(actual.isAllowed(HttpMethod.GET, summary, "EXAMINER")).isTrue();
            assertThat(actual.isAllowed(HttpMethod.GET, summary, "ADMIN")).isTrue();
            assertThat(actual.isAllowed(HttpMethod.GET, summary, "REQUESTER")).isFalse();
            // 다른 메서드는 규칙이 없어 거부된다
            assertThat(actual.isAllowed(HttpMethod.POST, summary, "ADMIN")).isFalse();
        }

        @Test
        @DisplayName("기존 장애 목록·상세·영향 의뢰 권한은 바뀌지 않는다 (EXAMINER·ADMIN)")
        void incidentRulesUnchanged() {
            String detail = "/api/v1/equipment-incidents/71de0c4e-1b2a-4c3d-9e8f-0a1b2c3d4e5f";
            for (String path : List.of("/api/v1/equipment-incidents", detail, detail + "/referrals")) {
                assertThat(actual.isAllowed(HttpMethod.GET, path, "EXAMINER")).as(path).isTrue();
                assertThat(actual.isAllowed(HttpMethod.GET, path, "ADMIN")).as(path).isTrue();
                assertThat(actual.isAllowed(HttpMethod.GET, path, "REQUESTER")).as(path).isFalse();
            }
        }
    }

    private static RolePolicy loadActualPolicy() {
        try {
            var sources = new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"));
            RolePolicyProperties properties = new Binder(ConfigurationPropertySources.from(sources))
                    .bind("gateway.policy", RolePolicyProperties.class)
                    .orElseThrow(() -> new IllegalStateException("gateway.policy가 없습니다"));
            return new RolePolicy(properties);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static RolePolicyProperties.Group group(List<String> roles, String... rules) {
        return new RolePolicyProperties.Group(roles, List.of(rules));
    }
}
