package healoworld.institution_service.presentation.dto.response;

import healoworld.institution_service.domain.entity.EquipmentStatusHistory;
import healoworld.institution_service.domain.enums.EquipmentStatus;
import java.time.LocalDateTime;
import java.util.UUID;

public record EquipmentStatusHistoryResponse(
        UUID id,
        UUID equipmentId,
        EquipmentStatus fromStatus,
        EquipmentStatus toStatus,
        String reason,
        UUID changedBy,
        LocalDateTime changedAt
) {
    public static EquipmentStatusHistoryResponse from(EquipmentStatusHistory history) {
        return new EquipmentStatusHistoryResponse(
                history.getId(),
                history.getEquipmentId(),
                history.getFromStatus(),
                history.getToStatus(),
                history.getReason(),
                history.getChangedBy(),
                history.getChangedAt()
        );
    }
}
