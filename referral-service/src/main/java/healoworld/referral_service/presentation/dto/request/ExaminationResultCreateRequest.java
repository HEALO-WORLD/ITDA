package healoworld.referral_service.presentation.dto.request;

import jakarta.validation.constraints.NotBlank;

// POST /api/v1/examinations/{examinationId}/results 요청 (summary 필수, notes 선택)
// ExaminationController에서 @Valid로 검증한다
public record ExaminationResultCreateRequest(
        @NotBlank(message = "검사 결과 요약(summary)은 필수입니다.") String summary,
        String notes) {
}
