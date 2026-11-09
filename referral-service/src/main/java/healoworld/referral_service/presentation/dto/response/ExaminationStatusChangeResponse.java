package healoworld.referral_service.presentation.dto.response;

import healoworld.referral_service.domain.entity.Examination;
import healoworld.referral_service.domain.enums.ExaminationStatus;

import java.time.LocalDateTime;
import java.util.UUID;

// PATCH /api/v1/examinations/{examinationId}/status 응답
// (명세: examinationId, referralId, previousStatus, currentStatus, updatedAt)
// 같은 상태로 다시 요청한 경우 previousStatus와 currentStatus가 같고 updatedAt도 기존 값 그대로다
public record ExaminationStatusChangeResponse(UUID examinationId, UUID referralId,
                                              ExaminationStatus previousStatus, ExaminationStatus currentStatus,
                                              LocalDateTime updatedAt) {

    public static ExaminationStatusChangeResponse of(ExaminationStatus previousStatus, Examination current) {
        return new ExaminationStatusChangeResponse(current.getId(), current.getReferralId(),
                previousStatus, current.getStatus(), current.getUpdatedAt());
    }

    public static ExaminationStatusChangeResponse unchanged(Examination current) {
        return of(current.getStatus(), current);
    }
}
