package healoworld.referral_service.presentation.dto.request;

import healoworld.referral_service.domain.enums.ExaminationStatus;
import jakarta.validation.constraints.NotNull;

public record ExaminationStatusChangeRequest(
        @NotNull(message = "검사 상태는 필수입니다.")
        ExaminationStatus status
) {
}