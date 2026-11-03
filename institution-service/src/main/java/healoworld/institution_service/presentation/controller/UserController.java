package healoworld.institution_service.presentation.controller;

import healoworld.institution_service.application.service.user.UserService;
import healoworld.institution_service.global.dto.ApiResponse;
import healoworld.institution_service.presentation.dto.response.MyInfoResponse;
import healoworld.institution_service.presentation.dto.response.UserSummaryResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/users")
public class UserController {

    private final UserService userService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<UserSummaryResponse>>> getUsers(
            @RequestParam(required = false) Boolean active) {
        return ResponseEntity.ok(ApiResponse.ok("사용자 목록입니다.", userService.getUsers(active)));
    }

    @PatchMapping("/{userId}/approve")
    public ResponseEntity<ApiResponse<UserSummaryResponse>> approve(@PathVariable UUID userId) {
        return ResponseEntity.ok(ApiResponse.ok("가입이 승인되었습니다.", userService.approve(userId)));
    }

    @GetMapping("/me")
    public ResponseEntity<ApiResponse<MyInfoResponse>> getMyInfo(
            @RequestHeader("X-User-Id") UUID userId) {
        return ResponseEntity.ok(ApiResponse.ok("내 정보입니다.", userService.getMyInfo(userId)));
    }
}
