package healoworld.referral_service.presentation.dto.response;

import healoworld.referral_service.domain.entity.IncidentReferral;
import healoworld.referral_service.domain.enums.IncidentProcessingStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.UUID;

// GET /api/v1/equipment-incidents/{incidentId}/referrals 목록 항목
// 명세 8.2 필드 + 영향 예약 ID(reservationId)
public record IncidentReferralResponse(
        @Schema(description = "후속 처리 작업 ID") UUID id,
        @Schema(description = "장애 ID") UUID incidentId,
        @Schema(description = "영향받은 의뢰 ID") UUID referralId,
        @Schema(description = "영향받은 예약 ID") UUID reservationId,
        @Schema(description = "후속 처리 상태") IncidentProcessingStatus processingStatus,
        @Schema(description = "FAILED 작업을 다시 선점한 횟수") int retryCount,
        @Schema(description = "다음 재시도 가능 시각 (없으면 null)") LocalDateTime nextRetryAt,
        @Schema(description = "작업 선점 만료 시각 (선점 중이 아니면 null)") LocalDateTime lockedUntil,
        @Schema(description = "마지막 실패의 분류와 진단 코드. ADMIN에게만 제공되고 EXAMINER에게는 항상 null",
                example = "[RETRYABLE] DataIntegrityViolationException sqlState=23502")
        String lastError,
        @Schema(description = "처리 결과 코드 (RESCHEDULE_REQUIRED, ALREADY_BLOCKED, SKIPPED_NOT_CURRENT, SKIPPED_CANCELED). 성공 전에는 null",
                example = "RESCHEDULE_REQUIRED")
        String resultCode,
        @Schema(description = "후속 처리 성공 시각 (성공 전에는 null)") LocalDateTime processedAt) {

    // includeLastError가 false이면 lastError를 null로 내보낸다 (DB에 저장된 값은 그대로 둔다)
    // lastError에는 예외 원문 대신 실패 분류와 진단 정보(예외 종류·오류 코드·SQLState 또는 ID·상태값)만 저장된다
    // 그래도 내부 운영 정보이므로 노출 여부를 호출하는 쪽이 반드시 정하게 한다
    public static IncidentReferralResponse from(IncidentReferral task, boolean includeLastError) {
        return new IncidentReferralResponse(task.getId(), task.getIncident().getId(), task.getReferralId(),
                task.getReservationId(), task.getProcessingStatus(), task.getRetryCount(), task.getNextRetryAt(),
                task.getLockedUntil(), includeLastError ? task.getLastError() : null, task.getResultCode(),
                task.getProcessedAt());
    }
}
