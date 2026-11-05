package healoworld.institution_service.presentation.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalTime;
import java.util.List;

@Getter
@NoArgsConstructor
public class EquipmentOperatingHoursUpdateRequest {

    @NotNull(message = "운영시간 목록은 필수입니다.")
    @Size(max = 7, message = "운영시간은 최대 7개 요일까지 설정할 수 있습니다.")
    private List<@NotNull @Valid OperatingHours> operatingHours;

    @Getter
    @NoArgsConstructor
    public static class OperatingHours {

        @NotNull
        @Min(1)
        @Max(7)
        private Integer week;

        @NotNull
        private LocalTime startTime;

        @NotNull
        private LocalTime endTime;
    }
}