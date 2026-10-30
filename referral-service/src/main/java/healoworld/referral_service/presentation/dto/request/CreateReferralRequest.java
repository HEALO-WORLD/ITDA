package healoworld.referral_service.presentation.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.util.UUID;

@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class CreateReferralRequest {
    @NotNull(message = "의뢰 기관 ID는 필수입니다.")
    private UUID referringInstitutionId;
    @NotNull(message = "검사 종류는 필수입니다.")
    private UUID examTypeId;
    @NotBlank(message = "검사자 이름은 필수입니다.")
    private String patientName;
    @NotNull(message = "멱등 키는 필수입니다.")
    private UUID idempotencyKey;
    @NotNull
    @Valid
    private CreateReservationRequest reservation;
}
