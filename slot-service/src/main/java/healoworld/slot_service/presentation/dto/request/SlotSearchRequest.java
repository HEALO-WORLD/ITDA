package healoworld.slot_service.presentation.dto.request;

import healoworld.slot_service.domain.enums.SlotStatus;
import healoworld.slot_service.domain.enums.SlotView;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
public class SlotSearchRequest {

    @NotNull(message = "검색 시작 일시는 필수입니다.")
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private OffsetDateTime from;

    @NotNull(message = "검색 종료 일시는 필수입니다.")
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private OffsetDateTime to;

    private UUID examTypeId;

    private UUID institutionId;

    private UUID equipmentId;

    private SlotStatus status;

    @NotNull(message = "조회 구분은 필수입니다.")
    private SlotView view = SlotView.SEARCH;

    @NotNull(message = "페이지 번호는 필수입니다.")
    @Min(value = 0, message = "페이지 번호는 0 이상이어야 합니다.")
    private Integer page = 0;

    @NotNull(message = "페이지 크기는 필수입니다.")
    @Min(value = 1, message = "페이지 크기는 1 이상이어야 합니다.")
    @Max(value = 100, message = "페이지 크기는 100 이하여야 합니다.")
    private Integer size = 20;

}