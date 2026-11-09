package healoworld.slot_service.presentation.dto.request;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@NoArgsConstructor
public class SlotCreateRequest {

    @NotNull(message = "장비 ID는 필수입니다.")
    private UUID equipmentId;

    @NotNull(message = "검사 유형 ID는 필수입니다.")
    private UUID examTypeId;

    @NotNull(message = "시작 일시는 필수입니다.")
    @Future(message = "시작 일시는 현재보다 이후여야 합니다.")
    private OffsetDateTime startsAt;

    @NotNull(message = "종료 일시는 필수입니다.")
    private OffsetDateTime endsAt;
}