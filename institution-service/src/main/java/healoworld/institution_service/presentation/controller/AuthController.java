package healoworld.institution_service.presentation.controller;

import healoworld.institution_service.application.service.user.AuthService;
import healoworld.institution_service.application.service.user.UserService;
import healoworld.institution_service.global.dto.ApiResponse;
import healoworld.institution_service.presentation.dto.request.LoginRequest;
import healoworld.institution_service.presentation.dto.request.SignupRequest;
import healoworld.institution_service.presentation.dto.response.SignupResponse;
import healoworld.institution_service.presentation.dto.response.TokenResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final UserService userService;
    private final AuthService authService;

    @PostMapping("/signup")
    public ResponseEntity<ApiResponse<SignupResponse>> signup(@Valid @RequestBody SignupRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("회원가입이 완료되었습니다. 관리자 승인 후 로그인할 수 있습니다.",
                        userService.signup(request)));
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<TokenResponse>> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(ApiResponse.ok("로그인 하였습니다.", authService.login(request)));
    }
}
