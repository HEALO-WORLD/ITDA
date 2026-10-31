package healoworld.gateway_service.policy;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;

@Component
@RequiredArgsConstructor
public class RolePolicy {

    private final RolePolicyProperties properties;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    public boolean isAlllowed(HttpMethod method, String path, String role) {
        return properties.rules().stream()
                .filter(rule -> matches(rule, method, path))
                .findFirst()
                .map(rule -> rule.roles().contains(role))
                .orElse(true);  // 맞는 규칙이 없으면 통과 (로그인만 확인)
    }

    private boolean matches(RolePolicyProperties.Rule rule, HttpMethod method, String path) {
        return rule.methods().contains(method.name())
                && pathMatcher.match(rule.path(), path);
    }
}
