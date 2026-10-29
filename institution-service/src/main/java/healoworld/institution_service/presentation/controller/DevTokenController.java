package healoworld.institution_service.presentation.controller;


import healoworld.institution_service.application.service.auth.AuthService;
import healoworld.institution_service.application.service.auth.DevTestAccount;
import healoworld.institution_service.global.dto.ApiResponse;
import healoworld.institution_service.presentation.dto.response.TokenResponse;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * @PathVariable DevTestAccount: 안전장치1. 목록에 없는 이름이면 발급 전에 요청 거절
 * @ConditionalOnProperty(..): 안전장치2. .env의 DEV_TOKEN_ENABLED=true일 때만 이 컨트롤러가 아예 생성됨. 꺼져있으면 404
 * @RequestMapping(..): 안전장치3. Gateway밖에서는 이 주소에 닿을 수 없음.
 *
 */

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/internal/dev/tokens")
@ConditionalOnProperty(prefix = "jwt.dev-token", name = "enabled", havingValue = "true")
public class DevTokenController {
    private final AuthService authService;

    // 실수로 켜져있을 때 경고용 로그
    @PostConstruct
    void warn() {
        log.warn("[DEV] 개발용 토큰 발급 API가 활성화되어 있습니다. 배포 환경에서는 DEV_TOKEN_ENABLED를 설정하지 마세요.");
    }

    @PostMapping("/{account}")
    public ResponseEntity<ApiResponse<TokenResponse>> issue(@PathVariable DevTestAccount account) {
        return ResponseEntity.ok(ApiResponse.ok("개발용 토큰이 발급되었습니다.", authService.issueDevToken(account)));
    }
}
