package healoworld.referral_service.presentation.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class CreateReservationRequest {
    @NotNull(message = "슬롯 ID는 필수입니다.")
    private UUID slotId;
    @NotNull(message = "검사 기관 ID는 필수입니다.")
    private UUID examInstitutionId;
    @NotNull(message = "장비 ID는 필수입니다.")
    private UUID equipmentId;
    @NotNull(message = "검사 일자는 필수입니다.")
    private LocalDateTime examDate;
    @NotNull(message = "검사 시작 시간은 필수입니다.")
    private LocalDateTime startTime;
    @NotNull(message = "검사 종료 시간은 필수입니다.")
    private LocalDateTime endTime;
}
