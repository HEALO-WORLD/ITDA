package healoworld.slot_service.global.deadletter;

import healoworld.slot_service.global.dto.ApiResponse;
import healoworld.slot_service.global.dto.PagedResponse;
import io.swagger.v3.oas.annotations.Parameter;
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

    @GetMapping
    public ResponseEntity<ApiResponse<PagedResponse<DeadLetterResponse>>> getDeadLetters(
            @Parameter(description = "요청자 권한") @RequestHeader("X-Role") String role,
            @ParameterObject @ModelAttribute DeadLetterSearchCondition cond
    ) {
        PagedResponse<DeadLetterResponse> response = deadLetterService.getDeadLetters(role, cond);
        return ResponseEntity.ok(ApiResponse.ok("실패 메시지 목록 조회가 완료되었습니다.", response));
    }

    @GetMapping("/{deadLetterId}")
    public ResponseEntity<ApiResponse<DeadLetterDetailResponse>> getDeadLetter(
            @Parameter(description = "요청자 권한") @RequestHeader("X-Role") String role,
            @Parameter(description = "실패 메시지 ID") @PathVariable UUID deadLetterId
    ) {
        DeadLetterDetailResponse response = deadLetterService.getDeadLetter(role, deadLetterId);
        return ResponseEntity.ok(ApiResponse.ok("실패 메시지 조회가 완료되었습니다.", response));
    }

    @PostMapping("/{deadLetterId}/reprocess")
    public ResponseEntity<ApiResponse<DeadLetterDetailResponse>> reprocess(
            @Parameter(description = "요청자 ID") @RequestHeader("X-User-Id") UUID userId,
            @Parameter(description = "요청자 권한") @RequestHeader("X-Role") String role,
            @Parameter(description = "실패 메시지 ID") @PathVariable UUID deadLetterId
    ) {
        DeadLetterDetailResponse response = deadLetterService.reprocess(userId, role, deadLetterId);
        return ResponseEntity.ok(ApiResponse.ok("실패 메시지를 원래 토픽으로 다시 발행했습니다.", response));
    }

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
