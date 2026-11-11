package healoworld.institution_service.presentation.controller;

import healoworld.institution_service.application.service.user.AuthService;
import healoworld.institution_service.application.service.user.UserService;
import healoworld.institution_service.global.dto.ApiResponse;
import healoworld.institution_service.global.dto.ErrorResponse;
import healoworld.institution_service.presentation.dto.request.LoginRequest;
import healoworld.institution_service.presentation.dto.request.RefreshTokenRequest;
import healoworld.institution_service.presentation.dto.request.SignupRequest;
import healoworld.institution_service.presentation.dto.response.SignupResponse;
import healoworld.institution_service.presentation.dto.response.TokenResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// Swagger 응답 어노테이션은 공통 응답 DTO(ApiResponse)와 이름이 같아 완전한 이름으로 쓴다
// 성공 응답은 content를 비워 springdoc이 반환형(ApiResponse<T>)으로 스키마를 만들게 하고, 오류 응답만 ErrorResponse를 지정한다
@Tag(name = "Auth", description = """
        회원가입·로그인·토큰 관리 API (인증 불필요).
        """)
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final UserService userService;
    private final AuthService authService;

    @Operation(summary = "회원가입",
            description = """
                    기관 소속 사용자를 등록합니다. 의뢰기관·검사기관만 가입할 수 있으며 관리자 승인 후 로그인할 수 있습니다.
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "가입 완료 (승인 대기)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = """
                    - VALIDATION_FAILED: loginId(4~50자)·password(8~64자)·institutionId 검증 실패
                    - INVALID_INPUT: 요청 본문 형식 오류
                    - SIGNUP_NOT_ALLOWED_INSTITUTION: 운영사(PLATFORM) 기관으로 가입 요청
                    """, content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "INSTITUTION_NOT_FOUND: 기관이 없거나 삭제됨",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "DUPLICATE_LOGIN_ID: 이미 사용 중인 아이디 (동시 가입 포함)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/signup")
    public ResponseEntity<ApiResponse<SignupResponse>> signup(@Valid @RequestBody SignupRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("회원가입이 완료되었습니다. 관리자 승인 후 로그인할 수 있습니다.",
                        userService.signup(request)));
    }

    @Operation(summary = "로그인",
            description = """
                    아이디·비밀번호를 확인하고 Access·Refresh Token을 발급합니다. 승인된 사용자만 로그인할 수 있습니다.
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "로그인 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = """
                    - VALIDATION_FAILED: loginId·password가 비어 있음
                    - INVALID_INPUT: 요청 본문 형식 오류
                    """, content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "INVALID_CREDENTIALS: 아이디가 없거나 비밀번호가 틀림",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "USER_NOT_APPROVED: 관리자 승인 전 사용자",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/login")
    public ResponseEntity<ApiResponse<TokenResponse>> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(ApiResponse.ok("로그인 하였습니다.", authService.login(request)));
    }

    @Operation(summary = "토큰 재발급",
            description = """
                    Refresh Token을 교체하고 새 토큰 쌍을 발급합니다. 유예 시간 이후 재사용이 감지되면 해당 토큰 계열을 폐기합니다.
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "재발급 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = """
                    - VALIDATION_FAILED: refreshToken이 비어 있음
                    - INVALID_INPUT: 요청 본문 형식 오류
                    """, content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = """
                    - INVALID_REFRESH_TOKEN: 서명·만료·종류가 잘못됨, 저장된 계열이 없음(로그아웃·폐기됨), 사용자가 삭제·비활성 상태
                    - REFRESH_TOKEN_REUSED: 유예 시간이 지난 뒤 이미 사용된 토큰을 다시 보냄 (계열 폐기)
                    """, content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/reissue")
    public ResponseEntity<ApiResponse<TokenResponse>> reissue(@Valid @RequestBody RefreshTokenRequest request) {
        return ResponseEntity.ok(ApiResponse.ok("토큰을 재발급 하였습니다.", authService.reissue(request)));
    }

    @Operation(summary = "로그아웃",
            description = """
                    Refresh Token 계열을 폐기해 재발급을 차단합니다. 기존 Access Token은 만료 전까지 유효합니다.
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "로그아웃 처리 (data는 null)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = """
                    - VALIDATION_FAILED: refreshToken이 비어 있음
                    - INVALID_INPUT: 요청 본문 형식 오류
                    """, content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> logout(@Valid @RequestBody RefreshTokenRequest request) {
        authService.logout(request);
        return ResponseEntity.ok(ApiResponse.ok("로그아웃 하였습니다.", null));
    }
}
