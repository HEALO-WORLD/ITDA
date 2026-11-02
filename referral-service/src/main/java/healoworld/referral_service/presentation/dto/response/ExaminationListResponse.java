package healoworld.referral_service.presentation.dto.response;

import healoworld.referral_service.domain.entity.Examination;
import healoworld.referral_service.domain.enums.ExaminationStatus;

import java.time.LocalDateTime;
import java.util.UUID;

public record ExaminationListResponse(
        UUID examinationId,
        UUID referralId,
        UUID reservationId,
        ExaminationStatus status,
        LocalDateTime startedAt,
        LocalDateTime completedAt
) {

    public static ExaminationListResponse from(Examination examination) {
        return new ExaminationListResponse(
                examination.getId(),
                examination.getReferralId(),
                examination.getReservationId(),
                examination.getStatus(),
                examination.getStartedAt(),
                examination.getCompletedAt()
        );
    }
}