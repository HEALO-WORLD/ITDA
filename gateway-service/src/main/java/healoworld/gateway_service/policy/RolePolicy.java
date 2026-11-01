package healoworld.gateway_service.policy;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Component
public class RolePolicy {

    private static final Set<String> VALID_ROLES = Set.of("REQUESTER", "EXAMINER", "ADMIN");
    private static final Set<String> VALID_METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE");

    private final AntPathMatcher pathMatcher = new AntPathMatcher();
    private final List<Rule> publicRules;
    private final List<Rule> roleRules;

    public RolePolicy(RolePolicyProperties properties) {
        this.publicRules = properties.publicRules().stream()
                .map(text -> Rule.parse(text, Set.of()))    // 공개 API는 역할이 없음
                .toList();

        this.roleRules = properties.groups().stream()
                .flatMap(group -> {
                    Set<String> roles = validateRoles(group.roles());
                    return group.rules().stream().map(text -> Rule.parse(text, roles));
                })
                .toList();

        validateNoDuplicates();
    }

    // 토큰 없이 허용되는 요청인가
    public boolean isPublic(HttpMethod method, String path) {
        return publicRules.stream().anyMatch(rule -> rule.matches(method, path, pathMatcher));
    }

    // 이 역할이 이 요청을 해도 되나 (가장 구체적인 규칙 적용, 없으면 거부)
    public boolean isAllowed(HttpMethod method, String path, String role) {
        Comparator<String> mostSpecificFirst = pathMatcher.getPatternComparator(path);
        return roleRules.stream()
                .filter(rule -> rule.matches(method, path, pathMatcher))
                .min((a, b) -> mostSpecificFirst.compare(a.path(), b.path()))
                .map(rule -> rule.roles().contains(role))
                .orElse(false);
    }

    private Set<String> validateRoles(List<String> roles) {
        for (String role : roles) {
            if (!VALID_ROLES.contains(role)) {
                throw new IllegalStateException("알 수 없는 역할: " + role);
            }
        }
        return Set.copyOf(roles);
    }

    // 같은 Method + Path 중복, 공개 API와 보호 API 겹침 검사
    private void validateNoDuplicates() {
        Set<String> seen = new HashSet<>();
        for (Rule rule : publicRules) {
            for (String method : rule.methods()) {
                if (!seen.add(method + " " + rule.path())) {
                    throw new IllegalStateException("중복 공개 정책: " + method + " " + rule.path());
                }
            }
        }
        for (Rule rule : roleRules) {
            for (String method : rule.methods()) {
                if (!seen.add(method + " " + rule.path())) {
                    throw new IllegalStateException("중복 정책: " + method + " " + rule.path());
                }
            }
        }
    }

    private record Rule(Set<String> methods, String path, Set<String> roles) {

        static Rule parse(String text, Set<String> roles) {
            String[] parts = text.trim().split("\\s+");
            if (parts.length != 2) {
                throw new IllegalStateException("잘못된 정책 형식: " + text);
            }
            Set<String> methods = Set.of(parts[0].split(","));
            for (String method : methods) {
                if (!VALID_METHODS.contains(method)) {
                    throw new IllegalStateException("알 수 없는 메서드: " + method + " (" + text + ")");
                }
            }
            if (parts[1].contains("**")) {
                throw new IllegalStateException("** 사용 금지: " + text);
            }
            return new Rule(methods, parts[1], roles);
        }

        boolean matches(HttpMethod method, String path, AntPathMatcher matcher) {
            return methods.contains(method.name()) && matcher.match(this.path, path);
        }
    }
}