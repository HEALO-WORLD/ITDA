package healoworld.slot_service.global.deadletter;

import healoworld.slot_service.global.dto.ApiResponse;
import healoworld.slot_service.global.dto.ErrorResponse;
import healoworld.slot_service.global.dto.PagedResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

// X-Role은 Gateway(JwtAuthenticationFilter)가 외부 요청의 같은 헤더를 지우고 JWT 값으로 채워 전달한다
@Tag(name = "Dead Letter (Admin)", description = "Kafka 실패 메시지(DLT) 관리자 API")
@RestController
@RequestMapping("/api/v1/slot-dead-letters")
@RequiredArgsConstructor
public class DeadLetterAdminController {
    private final DeadLetterService deadLetterService;

    @Operation(summary = "[ADMIN] 실패 메시지 목록 조회",
            description = """
                    Slot Service의 실패 메시지를 최신순으로 조회합니다. 상태별 조회가 가능하며 유효하지 않은 페이지 값은 기본값으로 보정됩니다.
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = """
                    - MISSING_HEADER: X-Role 헤더 누락
                    - VALIDATION_FAILED: status·page·size 값 형식 오류
                    """, content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "ADMIN_ONLY: ADMIN이 아님",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping
    public ResponseEntity<ApiResponse<PagedResponse<DeadLetterResponse>>> getDeadLetters(
            @Parameter(description = "요청자 권한") @RequestHeader("X-Role") String role,
            @ParameterObject @ModelAttribute DeadLetterSearchCondition cond
    ) {
        PagedResponse<DeadLetterResponse> response = deadLetterService.getDeadLetters(role, cond);
        return ResponseEntity.ok(ApiResponse.ok("실패 메시지 목록 조회가 완료되었습니다.", response));
    }

    @Operation(summary = "[ADMIN] 실패 메시지 상세 조회",
            description = """
                    실패 메시지의 원본 토픽·이벤트 정보·payload·실패 원인·처리 상태를 조회합니다.
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = """
                    - MISSING_HEADER: X-Role 헤더 누락
                    - INVALID_INPUT: deadLetterId가 UUID 형식이 아님
                    """, content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "ADMIN_ONLY: ADMIN이 아님",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "DEAD_LETTER_NOT_FOUND: 실패 메시지가 없음",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/{deadLetterId}")
    public ResponseEntity<ApiResponse<DeadLetterDetailResponse>> getDeadLetter(
            @Parameter(description = "요청자 권한") @RequestHeader("X-Role") String role,
            @Parameter(description = "실패 메시지 ID") @PathVariable UUID deadLetterId
    ) {
        DeadLetterDetailResponse response = deadLetterService.getDeadLetter(role, deadLetterId);
        return ResponseEntity.ok(ApiResponse.ok("실패 메시지 조회가 완료되었습니다.", response));
    }

    @Operation(summary = "[ADMIN] 실패 메시지 재처리 (원래 토픽으로 재발행)",
            description = """
                    대기 중인 실패 메시지를 원래 Kafka 토픽으로 재발행합니다. 발행 성공은 소비자 처리 성공을 의미하지 않으며 중복 재처리는 차단됩니다.
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "재발행 완료 (상태 REPROCESSED)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = """
                    - MISSING_HEADER: X-User-Id·X-Role 헤더 누락
                    - INVALID_INPUT: deadLetterId·X-User-Id가 UUID 형식이 아님
                    """, content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "ADMIN_ONLY: ADMIN이 아님",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "DEAD_LETTER_NOT_FOUND: 실패 메시지가 없음",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "DEAD_LETTER_ALREADY_RESOLVED: 이미 재처리되었거나 무시된 메시지 (PENDING이 아님)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "503", description = "DEAD_LETTER_REPUBLISH_FAILED: Kafka 재발행 실패 또는 시간 초과 (상태는 PENDING 유지)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/{deadLetterId}/reprocess")
    public ResponseEntity<ApiResponse<DeadLetterDetailResponse>> reprocess(
            @Parameter(description = "요청자 ID") @RequestHeader("X-User-Id") UUID userId,
            @Parameter(description = "요청자 권한") @RequestHeader("X-Role") String role,
            @Parameter(description = "실패 메시지 ID") @PathVariable UUID deadLetterId
    ) {
        DeadLetterDetailResponse response = deadLetterService.reprocess(userId, role, deadLetterId);
        return ResponseEntity.ok(ApiResponse.ok("실패 메시지를 원래 토픽으로 다시 발행했습니다.", response));
    }

    @Operation(summary = "[ADMIN] 실패 메시지 무시 처리",
            description = """
                    대기 중인 실패 메시지를 IGNORED로 변경합니다. Kafka 재발행 없이 기록을 유지하며 되돌릴 수 없습니다.
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "무시 처리 완료 (상태 IGNORED)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = """
                    - MISSING_HEADER: X-User-Id·X-Role 헤더 누락
                    - INVALID_INPUT: deadLetterId·X-User-Id가 UUID 형식이 아님
                    """, content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "ADMIN_ONLY: ADMIN이 아님",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "DEAD_LETTER_NOT_FOUND: 실패 메시지가 없음",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "DEAD_LETTER_ALREADY_RESOLVED: 이미 재처리되었거나 무시된 메시지 (PENDING이 아님)",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/{deadLetterId}/ignore")
    public ResponseEntity<ApiResponse<DeadLetterDetailResponse>> ignore(
            @Parameter(description = "요청자 ID") @RequestHeader("X-User-Id") UUID userId,
            @Parameter(description = "요청자 권한") @RequestHeader("X-Role") String role,
            @Parameter(description = "실패 메시지 ID") @PathVariable UUID deadLetterId
    ) {
        DeadLetterDetailResponse response = deadLetterService.ignore(userId, role, deadLetterId);
        return ResponseEntity.ok(ApiResponse.ok("실패 메시지를 무시 처리했습니다.", response));
    }
}
