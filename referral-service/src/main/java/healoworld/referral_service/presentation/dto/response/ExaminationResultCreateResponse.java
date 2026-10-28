package healoworld.referral_service.presentation.dto.response;

import healoworld.referral_service.domain.entity.ExaminationResult;

import java.time.LocalDateTime;
import java.util.UUID;

// POST /api/v1/examinations/{examinationId}/results 응답 (명세: resultId, examinationId, registeredAt)
public record ExaminationResultCreateResponse(UUID resultId, UUID examinationId, LocalDateTime registeredAt) {

    public static ExaminationResultCreateResponse from(ExaminationResult result) {
        return new ExaminationResultCreateResponse(result.getId(), result.getExamination().getId(),
                result.getRegisteredAt());
    }
}
