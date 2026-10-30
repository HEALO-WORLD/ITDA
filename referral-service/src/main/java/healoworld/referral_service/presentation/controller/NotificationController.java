package healoworld.referral_service.presentation.controller;

import healoworld.referral_service.global.dto.ApiResponse;
import healoworld.referral_service.global.dto.PagedResponse;
import healoworld.referral_service.presentation.dto.request.NotificationPageRequest;
import healoworld.referral_service.presentation.dto.response.NotificationReadResponse;
import healoworld.referral_service.presentation.dto.response.NotificationResponse;
import healoworld.referral_service.presentation.dto.response.UnreadCountResponse;
import healoworld.referral_service.application.service.NotificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
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
@Tag(name = "Notification", description = "사용자 알림 API")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    private static final String USER_ID_HEADER = "X-User-Id";

    private final NotificationService notificationService;

    @Operation(summary = "내 알림 목록 조회",
            description = """
                    요청자 본인의 알림만 조회합니다. referralId와 isRead로 필터링할 수 있습니다.

                    - 400 MISSING_HEADER: X-User-Id 누락
                    - 400 INVALID_INPUT: 헤더 또는 검색 조건의 형식이 잘못되었거나 정렬 조건이 허용되지 않음
                    """,
            parameters = {
                    @Parameter(name = "page", in = ParameterIn.QUERY,
                            description = "페이지 번호 (0부터). 숫자가 아니거나 음수이면 0",
                            schema = @Schema(type = "integer", defaultValue = "0")),
                    @Parameter(name = "size", in = ParameterIn.QUERY,
                            description = "페이지 크기 (10, 30, 50 외에는 10)",
                            schema = @Schema(type = "integer", defaultValue = "10")),
                    @Parameter(name = "sort", in = ParameterIn.QUERY,
                            description = """
                                    정렬 조건. 필드는 createdAt, readAt, 방향은 asc, desc만 허용합니다. 방향을 생략하면 asc입니다.
                                    sort를 여러 번 보내면 순서대로 적용하며, 하나라도 잘못되면 400 INVALID_INPUT입니다. 생략하면 createdAt,desc입니다.
                                    """,
                            array = @ArraySchema(schema = @Schema(type = "string", example = "createdAt,desc")))
            })
    @GetMapping
    public ResponseEntity<ApiResponse<PagedResponse<NotificationResponse>>> getMyNotifications(
            @Parameter(description = "요청자 ID") @RequestHeader(USER_ID_HEADER) UUID userId,
            @Parameter(description = "의뢰 ID. 해당 의뢰의 알림만 조회") @RequestParam(required = false) UUID referralId,
            @Parameter(description = "읽음 여부. 생략하면 전체 조회") @RequestParam(required = false) Boolean isRead,
            @Parameter(hidden = true) HttpServletRequest request) {
        Pageable pageable = NotificationPageRequest.of(request.getParameter("page"), request.getParameter("size"),
                request.getParameterValues("sort"));
        return ResponseEntity.ok(ApiResponse.ok("알림 목록 조회 성공",
                notificationService.getMyNotifications(userId, referralId, isRead, pageable)));
    }

    @Operation(summary = "읽지 않은 알림 수 조회",
            description = """
                    요청자 본인의 읽지 않은 알림 수를 조회합니다.

                    - 400 MISSING_HEADER: X-User-Id 누락
                    - 400 INVALID_INPUT: X-User-Id 형식이 잘못됨
                    """)
    @GetMapping("/unread-count")
    public ResponseEntity<ApiResponse<UnreadCountResponse>> countUnread(
            @Parameter(description = "요청자 ID") @RequestHeader(USER_ID_HEADER) UUID userId) {
        return ResponseEntity.ok(ApiResponse.ok("안 읽은 알림 수 조회 성공", notificationService.countUnread(userId)));
    }

    @Operation(summary = "알림 읽음 처리",
            description = """
                    요청자 본인의 알림을 읽음 처리합니다.
                    이미 읽은 알림이면 변경하지 않고 처음 읽은 시각을 그대로 반환합니다.

                    - 400 MISSING_HEADER: X-User-Id 누락
                    - 400 INVALID_INPUT: 헤더 또는 알림 ID의 형식이 잘못됨
                    - 403 NOTIFICATION_ACCESS_DENIED: 다른 사용자의 알림
                    - 404 NOTIFICATION_NOT_FOUND: 알림이 없음
                    """)
    @PatchMapping("/{notificationId}/read")
    public ResponseEntity<ApiResponse<NotificationReadResponse>> markAsRead(
            @Parameter(description = "요청자 ID") @RequestHeader(USER_ID_HEADER) UUID userId,
            @Parameter(description = "알림 ID") @PathVariable UUID notificationId) {
        return ResponseEntity.ok(ApiResponse.ok("알림 읽음 처리 성공", notificationService.markAsRead(userId, notificationId)));
    }
}
