package healoworld.slot_service.presentation.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record EquipmentDowntimeRecoveryRequest(
        @NotBlank @Size(max = 500) String reason
) {

}
