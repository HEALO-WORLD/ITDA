package healoworld.referral_service.presentation.dto.request;

import jakarta.validation.constraints.NotBlank;

// POST /api/v1/examinations/{examinationId}/results 요청 (summary 필수, notes 선택)
// Controller는 인증·인가 계약 확정 후 추가하며, 그때 @Valid로 이 검증이 적용된다
public record ExaminationResultCreateRequest(
        @NotBlank(message = "검사 결과 요약(summary)은 필수입니다.") String summary,
        String notes) {
}
