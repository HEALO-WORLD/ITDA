package healoworld.referral_service.presentation.controller;

import healoworld.referral_service.application.service.InstitutionNotificationService;
import healoworld.referral_service.domain.enums.RoleType;
import healoworld.referral_service.global.dto.ApiResponse;
import healoworld.referral_service.global.dto.PagedResponse;
import healoworld.referral_service.global.exception.BaseException;
import healoworld.referral_service.global.exception.ErrorCode;
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
// X-Role·X-Institution-Id도 Gateway(JwtAuthenticationFilter)가 외부 요청의 같은 헤더를 지우고 JWT 값으로 채워 전달한다
//  - EXAMINER: 소속 검사기관(X-Institution-Id)의 공통 알림을 조회한다. 읽음 여부·읽음 처리·안 읽은 수는 요청 사용자(X-User-Id) 기준
//    (같은 기관의 다른 사용자 읽음 상태와 서로 영향 없음)
//  - 그 밖(REQUESTER, ADMIN, X-Role 없음): 기존과 같이 본인(X-User-Id) 개인 알림
//    X-Role은 선택 헤더로 두어, X-User-Id만 보내던 기존 호출은 그대로 개인 알림으로 동작한다
@Tag(name = "Notification", description = "사용자 알림 API")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    private static final String USER_ID_HEADER = "X-User-Id";
    private static final String ROLE_HEADER = "X-Role";
    private static final String INSTITUTION_ID_HEADER = "X-Institution-Id";

    private final NotificationService notificationService;
    private final InstitutionNotificationService institutionNotificationService;

    @Operation(summary = "내 알림 목록 조회",
            description = """
                    요청자 본인의 알림만 조회합니다. referralId와 isRead로 필터링할 수 있습니다.
                    검사기관 사용자(X-Role=EXAMINER)는 소속 검사기관의 공통 알림을 조회하며, isRead·readAt은 요청 사용자 본인의 읽음 기준입니다.

                    - 400 MISSING_HEADER: X-User-Id 누락, 또는 검사기관 사용자의 X-Institution-Id 누락
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
            @Parameter(description = "요청자 역할") @RequestHeader(value = ROLE_HEADER, required = false) RoleType role,
            @Parameter(description = "요청자 소속 기관 ID (검사기관 사용자 필수)")
            @RequestHeader(value = INSTITUTION_ID_HEADER, required = false) UUID institutionId,
            @Parameter(description = "의뢰 ID. 해당 의뢰의 알림만 조회") @RequestParam(required = false) UUID referralId,
            @Parameter(description = "읽음 여부. 생략하면 전체 조회") @RequestParam(required = false) Boolean isRead,
            @Parameter(hidden = true) HttpServletRequest request) {
        Pageable pageable = NotificationPageRequest.of(request.getParameter("page"), request.getParameter("size"),
                request.getParameterValues("sort"));
        PagedResponse<NotificationResponse> notifications = isExaminer(role)
                ? institutionNotificationService.getInstitutionNotifications(requireInstitution(institutionId),
                userId, referralId, isRead, pageable)
                : notificationService.getMyNotifications(userId, referralId, isRead, pageable);
        return ResponseEntity.ok(ApiResponse.ok("알림 목록 조회 성공", notifications));
    }

    @Operation(summary = "읽지 않은 알림 수 조회",
            description = """
                    요청자 본인의 읽지 않은 알림 수를 조회합니다.
                    검사기관 사용자(X-Role=EXAMINER)는 소속 검사기관 공통 알림 중 본인이 읽지 않은 수입니다.

                    - 400 MISSING_HEADER: X-User-Id 누락, 또는 검사기관 사용자의 X-Institution-Id 누락
                    - 400 INVALID_INPUT: 헤더 형식이 잘못됨
                    """)
    @GetMapping("/unread-count")
    public ResponseEntity<ApiResponse<UnreadCountResponse>> countUnread(
            @Parameter(description = "요청자 ID") @RequestHeader(USER_ID_HEADER) UUID userId,
            @Parameter(description = "요청자 역할") @RequestHeader(value = ROLE_HEADER, required = false) RoleType role,
            @Parameter(description = "요청자 소속 기관 ID (검사기관 사용자 필수)")
            @RequestHeader(value = INSTITUTION_ID_HEADER, required = false) UUID institutionId) {
        UnreadCountResponse unreadCount = isExaminer(role)
                ? institutionNotificationService.countUnread(requireInstitution(institutionId), userId)
                : notificationService.countUnread(userId);
        return ResponseEntity.ok(ApiResponse.ok("안 읽은 알림 수 조회 성공", unreadCount));
    }

    @Operation(summary = "알림 읽음 처리",
            description = """
                    요청자 본인의 알림을 읽음 처리합니다.
                    이미 읽은 알림이면 변경하지 않고 처음 읽은 시각을 그대로 반환합니다.
                    검사기관 사용자(X-Role=EXAMINER)는 소속 검사기관 공통 알림을 본인에 대해서만 읽음 처리합니다 (같은 기관 다른 사용자의 읽음 상태는 바뀌지 않음).

                    - 400 MISSING_HEADER: X-User-Id 누락, 또는 검사기관 사용자의 X-Institution-Id 누락
                    - 400 INVALID_INPUT: 헤더 또는 알림 ID의 형식이 잘못됨
                    - 403 NOTIFICATION_ACCESS_DENIED: 다른 사용자(검사기관 사용자는 다른 기관)의 알림
                    - 404 NOTIFICATION_NOT_FOUND: 알림이 없음
                    """)
    @PatchMapping("/{notificationId}/read")
    public ResponseEntity<ApiResponse<NotificationReadResponse>> markAsRead(
            @Parameter(description = "요청자 ID") @RequestHeader(USER_ID_HEADER) UUID userId,
            @Parameter(description = "요청자 역할") @RequestHeader(value = ROLE_HEADER, required = false) RoleType role,
            @Parameter(description = "요청자 소속 기관 ID (검사기관 사용자 필수)")
            @RequestHeader(value = INSTITUTION_ID_HEADER, required = false) UUID institutionId,
            @Parameter(description = "알림 ID") @PathVariable UUID notificationId) {
        NotificationReadResponse read = isExaminer(role)
                ? institutionNotificationService.markAsRead(requireInstitution(institutionId), userId, notificationId)
                : notificationService.markAsRead(userId, notificationId);
        return ResponseEntity.ok(ApiResponse.ok("알림 읽음 처리 성공", read));
    }

    private static boolean isExaminer(RoleType role) {
        return role == RoleType.EXAMINER;
    }

    // 검사기관 공통 알림은 소속 기관이 수신 대상이므로 기관 헤더가 없으면 처리하지 않는다 (다른 기관 알림으로 대체하지 않음)
    private static UUID requireInstitution(UUID institutionId) {
        if (institutionId == null) {
            throw new BaseException(ErrorCode.MISSING_HEADER);
        }
        return institutionId;
    }
}
