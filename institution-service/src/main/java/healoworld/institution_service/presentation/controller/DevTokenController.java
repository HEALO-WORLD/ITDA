package healoworld.institution_service.presentation.controller;


import healoworld.institution_service.application.service.user.AuthService;
import healoworld.institution_service.application.service.user.DevTestAccount;
import healoworld.institution_service.global.dto.ApiResponse;
import healoworld.institution_service.global.dto.ErrorResponse;
import healoworld.institution_service.presentation.dto.response.TokenResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
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

// DEV_TOKEN_ENABLED=true가 아니면 빈이 만들어지지 않아 Swagger 문서에도 나타나지 않는다 (@Hidden을 쓰지 않음)
@Tag(name = "Dev Token (Development Only)", description = """
        개발용 테스트 토큰 발급 API. DEV_TOKEN_ENABLED=true일 때만 활성화되며 서비스 포트로 직접 호출합니다.
        """)
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

    @Operation(summary = "[개발 전용] 테스트 계정 Access Token 발급",
            description = """
                    테스트 계정의 Access Token을 발급합니다. Refresh Token은 발급하지 않습니다.
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "발급 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "INVALID_INPUT: 목록에 없는 계정 이름",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/{account}")
    public ResponseEntity<ApiResponse<TokenResponse>> issue(
            @Parameter(description = "테스트 계정 이름") @PathVariable DevTestAccount account) {
        return ResponseEntity.ok(ApiResponse.ok("개발용 토큰이 발급되었습니다.", authService.issueDevToken(account)));
    }
}
