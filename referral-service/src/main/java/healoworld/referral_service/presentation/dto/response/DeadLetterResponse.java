package healoworld.referral_service.presentation.dto.response;

import healoworld.referral_service.global.deadletter.DeadLetterEvent;
import healoworld.referral_service.global.deadletter.DeadLetterStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.UUID;

// GET /api/v1/referral-dead-letters 목록 항목 (payload·key는 상세 조회에서만 준다)
public record DeadLetterResponse(
        @Schema(description = "실패 메시지 ID") UUID deadLetterId,
        @Schema(description = "원래 토픽") String originalTopic,
        @Schema(description = "원래 파티션") int originalPartition,
        @Schema(description = "원래 오프셋 (앱 로그의 topic-partition@offset으로 찾을 수 있음)") long originalOffset,
        @Schema(description = "이벤트 ID (헤더 원문, 없으면 null)") String eventId,
        @Schema(description = "이벤트 타입 (없으면 null)") String eventType,
        @Schema(description = "실패 원인 예외 클래스") String exceptionClass,
        @Schema(description = "실패 요약") String failureSummary,
        @Schema(description = "처리 상태") DeadLetterStatus status,
        @Schema(description = "저장 시각") LocalDateTime createdAt,
        @Schema(description = "재처리·무시 시각") LocalDateTime resolvedAt) {

    public static DeadLetterResponse from(DeadLetterEvent event) {
        return new DeadLetterResponse(event.getId(), event.getOriginalTopic(), event.getOriginalPartition(),
                event.getOriginalOffset(), event.getEventId(), event.getEventType(), event.getExceptionClass(),
                event.getFailureSummary(), event.getDeadLetterStatus(), event.getCreatedAt(), event.getResolvedAt());
    }
}
