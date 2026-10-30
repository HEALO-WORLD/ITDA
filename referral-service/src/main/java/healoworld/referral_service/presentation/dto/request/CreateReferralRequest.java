package healoworld.referral_service.presentation.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.util.UUID;

@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Schema(description = "의뢰 생성 + 첫 예약 접수 요청")
public class CreateReferralRequest {
    @Schema(description = "의뢰 기관 ID", example = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
    @NotNull(message = "의뢰 기관 ID는 필수입니다.")
    private UUID referringInstitutionId;
    @Schema(description = "검사 종류 ID", example = "dddddddd-dddd-dddd-dddd-dddddddddddd")
    @NotNull(message = "검사 종류는 필수입니다.")
    private UUID examTypeId;
    @Schema(description = "검사자(환자) 이름", example = "홍길동")
    @NotBlank(message = "검사자 이름은 필수입니다.")
    private String patientName;
    @Schema(description = "멱등 키. 같은 키로 재요청하면 기존 의뢰를 반환", example = "f47ac10b-58cc-4372-a567-0e02b2c3d479")
    @NotNull(message = "멱등 키는 필수입니다.")
    private UUID idempotencyKey;
    @Schema(description = "첫 예약 정보")
    @NotNull
    @Valid
    private CreateReservationRequest reservation;
}
