package healoworld.referral_service.presentation.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

// POST /api/v1/examinations/{examinationId}/results 요청 (summary 필수, notes 선택)
// ExaminationController에서 @Valid로 검증한다
public record ExaminationResultCreateRequest(
        @Schema(description = "검사 결과 요약 (필수, 공백 불가)", example = "검사 결과 특이사항 없음")
        @NotBlank(message = "검사 결과 요약(summary)은 필수입니다.") String summary,
        @Schema(description = "추가 소견 (선택)", example = "추가 검사 필요 없음")
        String notes) {
}
