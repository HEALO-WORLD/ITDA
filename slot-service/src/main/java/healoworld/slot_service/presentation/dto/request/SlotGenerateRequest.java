package healoworld.slot_service.presentation.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.UUID;

@Getter
@NoArgsConstructor
public class SlotGenerateRequest {

    @NotNull(message = "장비 ID는 필수입니다.")
    private UUID equipmentId;

    @NotNull(message = "검사 유형 ID는 필수입니다.")
    private UUID examTypeId;

    @NotNull(message = "생성 날짜는 필수입니다.")
    private LocalDate date;
}