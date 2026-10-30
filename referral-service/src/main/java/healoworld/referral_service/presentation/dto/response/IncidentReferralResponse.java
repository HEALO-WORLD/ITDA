package healoworld.referral_service.presentation.dto.response;

import healoworld.referral_service.domain.entity.IncidentReferral;
import healoworld.referral_service.domain.enums.IncidentProcessingStatus;

import java.time.LocalDateTime;
import java.util.UUID;

// GET /api/v1/equipment-incidents/{incidentId}/referrals 목록 항목
// 명세 8.2 필드 + 영향 예약 ID(reservationId)
public record IncidentReferralResponse(UUID id, UUID incidentId, UUID referralId, UUID reservationId,
                                       IncidentProcessingStatus processingStatus, int retryCount,
                                       LocalDateTime nextRetryAt, LocalDateTime lockedUntil, String lastError,
                                       String resultCode, LocalDateTime processedAt) {

    // includeLastError가 false이면 lastError를 null로 내보낸다 (DB에 저장된 원본은 그대로 둔다)
    // lastError에는 내부 예외 클래스명·메시지가 들어갈 수 있어 노출 여부를 호출하는 쪽이 반드시 정하게 한다
    public static IncidentReferralResponse from(IncidentReferral task, boolean includeLastError) {
        return new IncidentReferralResponse(task.getId(), task.getIncident().getId(), task.getReferralId(),
                task.getReservationId(), task.getProcessingStatus(), task.getRetryCount(), task.getNextRetryAt(),
                task.getLockedUntil(), includeLastError ? task.getLastError() : null, task.getResultCode(),
                task.getProcessedAt());
    }
}
