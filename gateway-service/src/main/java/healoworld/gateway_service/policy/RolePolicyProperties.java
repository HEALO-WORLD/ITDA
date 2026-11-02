package healoworld.gateway_service.policy;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "gateway.policy")
public record RolePolicyProperties(List<String> publicRules, List<Group> groups) {
    public RolePolicyProperties {
        publicRules = (publicRules == null) ? List.of() : publicRules;
        groups = (groups == null) ? List.of() : groups;
    }

    public record Group(List<String> roles, List<String> rules) {
    }
}
