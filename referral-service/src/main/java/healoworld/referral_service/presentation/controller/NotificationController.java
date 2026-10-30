package healoworld.referral_service.presentation.controller;

import healoworld.referral_service.global.dto.ApiResponse;
import healoworld.referral_service.global.dto.PagedResponse;
import healoworld.referral_service.presentation.dto.response.NotificationReadResponse;
import healoworld.referral_service.presentation.dto.response.NotificationResponse;
import healoworld.referral_service.presentation.dto.response.UnreadCountResponse;
import healoworld.referral_service.application.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

// 로그인 사용자 ID는 Gateway가 JWT 검증 후 넘겨주는 X-User-Id 헤더로 받는다 (JpaAuditingConfig와 같은 약속)
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    private static final String USER_ID_HEADER = "X-User-Id";

    private final NotificationService notificationService;

    @GetMapping
    public ResponseEntity<ApiResponse<PagedResponse<NotificationResponse>>> getMyNotifications(
            @RequestHeader(USER_ID_HEADER) UUID userId,
            @RequestParam(required = false) UUID referralId,
            @RequestParam(required = false) Boolean isRead,
            @PageableDefault(size = 10, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.ok("알림 목록 조회 성공",
                notificationService.getMyNotifications(userId, referralId, isRead, pageable)));
    }

    @GetMapping("/unread-count")
    public ResponseEntity<ApiResponse<UnreadCountResponse>> countUnread(
            @RequestHeader(USER_ID_HEADER) UUID userId) {
        return ResponseEntity.ok(ApiResponse.ok("안 읽은 알림 수 조회 성공", notificationService.countUnread(userId)));
    }

    @PatchMapping("/{notificationId}/read")
    public ResponseEntity<ApiResponse<NotificationReadResponse>> markAsRead(
            @RequestHeader(USER_ID_HEADER) UUID userId,
            @PathVariable UUID notificationId) {
        return ResponseEntity.ok(ApiResponse.ok("알림 읽음 처리 성공", notificationService.markAsRead(userId, notificationId)));
    }
}
