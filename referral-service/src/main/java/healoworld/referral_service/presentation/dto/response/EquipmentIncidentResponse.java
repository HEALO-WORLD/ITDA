package healoworld.referral_service.presentation.dto.response;

import healoworld.referral_service.domain.entity.EquipmentIncident;
import healoworld.referral_service.domain.enums.EquipmentIncidentStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.UUID;

// GET /api/v1/equipment-incidents 목록 항목
public record EquipmentIncidentResponse(
        @Schema(description = "장애 ID") UUID incidentId,
        @Schema(description = "장비 ID") UUID equipmentId,
        @Schema(description = "기관 ID") UUID institutionId,
        @Schema(description = "장애 사유") String reason,
        @Schema(description = "장애 상태") EquipmentIncidentStatus status,
        @Schema(description = "장애 발생 시각") LocalDateTime occurredAt,
        @Schema(description = "장애 영향 시작 시각") LocalDateTime startAt,
        @Schema(description = "장애 영향 종료 시각") LocalDateTime endAt,
        @Schema(description = "장애 등록 시각") LocalDateTime createdAt) {

    public static EquipmentIncidentResponse from(EquipmentIncident incident) {
        return new EquipmentIncidentResponse(incident.getId(), incident.getEquipmentId(), incident.getInstitutionId(),
                incident.getReason(), incident.getStatus(), incident.getOccurredAt(), incident.getStartAt(),
                incident.getEndAt(), incident.getCreatedAt());
    }
}
