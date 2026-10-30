package healoworld.referral_service.presentation.dto.response;

import healoworld.referral_service.domain.entity.EquipmentIncident;
import healoworld.referral_service.domain.enums.EquipmentIncidentStatus;

import java.time.LocalDateTime;
import java.util.UUID;

// GET /api/v1/equipment-incidents 목록 항목
public record EquipmentIncidentResponse(UUID incidentId, UUID equipmentId, UUID institutionId, String reason,
                                        EquipmentIncidentStatus status, LocalDateTime occurredAt,
                                        LocalDateTime startAt, LocalDateTime endAt, LocalDateTime createdAt) {

    public static EquipmentIncidentResponse from(EquipmentIncident incident) {
        return new EquipmentIncidentResponse(incident.getId(), incident.getEquipmentId(), incident.getInstitutionId(),
                incident.getReason(), incident.getStatus(), incident.getOccurredAt(), incident.getStartAt(),
                incident.getEndAt(), incident.getCreatedAt());
    }
}
