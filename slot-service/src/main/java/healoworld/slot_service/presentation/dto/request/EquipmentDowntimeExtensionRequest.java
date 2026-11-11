package healoworld.slot_service.presentation.dto.request;

import jakarta.validation.constraints.NotNull;
import java.time.OffsetDateTime;

public record EquipmentDowntimeExtensionRequest(@NotNull OffsetDateTime endedAt) {
}
