package healoworld.gateway_service.policy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

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

    private static RolePolicyProperties.Group group(List<String> roles, String... rules) {
        return new RolePolicyProperties.Group(roles, List.of(rules));
    }
}
