package healoworld.referral_service.presentation.dto.response;

import healoworld.referral_service.domain.entity.Examination;
import healoworld.referral_service.domain.enums.ExaminationStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.UUID;

// GET /api/v1/internal/examinations/by-referral/{referralId} 응답
// (명세: examinationId, referralId, examInstitutionId, status, startedAt, completedAt)
public record ExaminationResponse(
        @Schema(description = "검사 ID") UUID examinationId,
        @Schema(description = "의뢰 ID") UUID referralId,
        @Schema(description = "검사를 수행하는 검사기관 ID") UUID examInstitutionId,
        @Schema(description = "검사 상태") ExaminationStatus status,
        @Schema(description = "검사 시작 시각 (시작 전에는 null)") LocalDateTime startedAt,
        @Schema(description = "검사 완료 시각 (완료 전에는 null)") LocalDateTime completedAt) {

    public static ExaminationResponse from(Examination examination) {
        return new ExaminationResponse(examination.getId(), examination.getReferralId(),
                examination.getExamInstitutionId(), examination.getStatus(),
                examination.getStartedAt(), examination.getCompletedAt());
    }
}
