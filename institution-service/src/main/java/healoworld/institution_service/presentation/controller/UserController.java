package healoworld.institution_service.presentation.controller;

import healoworld.institution_service.application.service.user.UserService;
import healoworld.institution_service.global.dto.ApiResponse;
import healoworld.institution_service.global.dto.ErrorResponse;
import healoworld.institution_service.presentation.dto.response.MyInfoResponse;
import healoworld.institution_service.presentation.dto.response.UserSummaryResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@Tag(name = "User", description = """
        사용자 조회 및 가입 승인 API (인증 필요).
        """)
@SecurityRequirement(name = "gatewayBearerAuth")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/users")
public class UserController {

    private final UserService userService;

    @Operation(summary = "[ADMIN] 사용자 목록 조회",
            description = """
                    삭제되지 않은 사용자를 최신 가입순으로 조회합니다. 승인 여부로 필터링할 수 있습니다.
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "INVALID_INPUT: active 값이 true/false가 아님",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping
    public ResponseEntity<ApiResponse<List<UserSummaryResponse>>> getUsers(
            @Parameter(description = "승인 여부 필터 (생략 시 전체)") @RequestParam(required = false) Boolean active) {
        return ResponseEntity.ok(ApiResponse.ok("사용자 목록입니다.", userService.getUsers(active)));
    }

    @Operation(summary = "[ADMIN] 회원가입 승인",
            description = """
                    승인 대기 사용자를 승인해 로그인을 허용합니다.
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "승인 완료"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "INVALID_INPUT: userId가 UUID 형식이 아님",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "USER_NOT_FOUND: 사용자가 없거나 삭제됨",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "USER_ALREADY_APPROVED: 이미 승인된 사용자",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PatchMapping("/{userId}/approve")
    public ResponseEntity<ApiResponse<UserSummaryResponse>> approve(
            @Parameter(description = "승인할 사용자 ID") @PathVariable UUID userId) {
        return ResponseEntity.ok(ApiResponse.ok("가입이 승인되었습니다.", userService.approve(userId)));
    }

    @Operation(summary = "내 정보 조회",
            description = """
                    로그인한 사용자의 정보와 소속 기관을 조회합니다.
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = """
                    - MISSING_HEADER: X-User-Id 헤더 누락 (직접 호출 시)
                    - INVALID_INPUT: X-User-Id가 UUID 형식이 아님
                    """, content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "USER_NOT_FOUND: 사용자가 없거나 삭제됨",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/me")
    public ResponseEntity<ApiResponse<MyInfoResponse>> getMyInfo(
            @Parameter(description = "요청자 ID") @RequestHeader("X-User-Id") UUID userId) {
        return ResponseEntity.ok(ApiResponse.ok("내 정보입니다.", userService.getMyInfo(userId)));
    }
}
