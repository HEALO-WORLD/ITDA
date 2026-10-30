package healoworld.referral_service.presentation.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Schema(description = "예약 접수 요청")
public class CreateReservationRequest {
    @Schema(description = "선점할 슬롯 ID", example = "71de0c4e-1b2a-4c3d-9e8f-0a1b2c3d4e5f")
    @NotNull(message = "슬롯 ID는 필수입니다.")
    private UUID slotId;
    @Schema(description = "검사 기관 ID", example = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")
    @NotNull(message = "검사 기관 ID는 필수입니다.")
    private UUID examInstitutionId;
    @Schema(description = "장비 ID", example = "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee")
    @NotNull(message = "장비 ID는 필수입니다.")
    private UUID equipmentId;
    @Schema(description = "검사 일자", example = "2026-11-10T10:00:00")
    @NotNull(message = "검사 일자는 필수입니다.")
    private LocalDateTime examDate;
    @Schema(description = "검사 시작 시간", example = "2026-11-10T10:00:00")
    @NotNull(message = "검사 시작 시간은 필수입니다.")
    private LocalDateTime startTime;
    @Schema(description = "검사 종료 시간", example = "2026-11-10T11:00:00")
    @NotNull(message = "검사 종료 시간은 필수입니다.")
    private LocalDateTime endTime;
}
