package healoworld.institution_service.presentation.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
public class HeaderEchoController {

    @GetMapping("/api/v1/users/debug-headers")
    public Map<String, String> echo(
            @RequestHeader(value = "X-User-Id", required = false) String userId,
            @RequestHeader(value = "X-Institution-Id", required = false) String institutionId,
            @RequestHeader(value = "X-Institution-Type", required = false) String institutionType,
            @RequestHeader(value = "X-Role", required = false) String role) {

        Map<String, String> result = new LinkedHashMap<>();
        result.put("X-User-Id", userId);
        result.put("X-Institution-Id", institutionId);
        result.put("X-Institution-Type", institutionType);
        result.put("X-Role", role);
        return result;
    }
}