package healoworld.referral_service.presentation.dto.response;

import healoworld.referral_service.domain.entity.ExaminationResult;

import java.time.LocalDateTime;
import java.util.UUID;

public record ExaminationResultResponse(
        UUID id,
        UUID examinationId,
        UUID referralId,
        String summary,
        String notes,
        LocalDateTime registeredAt
) {
    public static ExaminationResultResponse from(ExaminationResult result) {
        return new ExaminationResultResponse(
                result.getId(),
                result.getExamination().getId(),
                result.getExamination().getReferralId(),
                result.getSummary(),
                result.getNotes(),
                result.getRegisteredAt()
        );
    }
}