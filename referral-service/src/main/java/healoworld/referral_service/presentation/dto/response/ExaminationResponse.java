package healoworld.referral_service.presentation.dto.response;

import healoworld.referral_service.domain.entity.Examination;
import healoworld.referral_service.domain.enums.ExaminationStatus;

import java.time.LocalDateTime;
import java.util.UUID;

// GET /api/v1/internal/examinations/by-referral/{referralId} 응답
// (명세: examinationId, referralId, examInstitutionId, status, startedAt, completedAt)
public record ExaminationResponse(UUID examinationId, UUID referralId, UUID examInstitutionId,
                                  ExaminationStatus status, LocalDateTime startedAt, LocalDateTime completedAt) {

    public static ExaminationResponse from(Examination examination) {
        return new ExaminationResponse(examination.getId(), examination.getReferralId(),
                examination.getExamInstitutionId(), examination.getStatus(),
                examination.getStartedAt(), examination.getCompletedAt());
    }
}
