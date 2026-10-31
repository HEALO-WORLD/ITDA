package healoworld.gateway_service.policy;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "gateway.policy")
public record RolePolicyProperties(List<Rule> rules) {
    public RolePolicyProperties {
        rules = (rules == null) ? List.of() : rules;
    }

    public record Rule(List<String> methods, String path, List<String> roles) {
    }
}
