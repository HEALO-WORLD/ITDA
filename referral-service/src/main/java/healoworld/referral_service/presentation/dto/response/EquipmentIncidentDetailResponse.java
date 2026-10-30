package healoworld.referral_service.presentation.dto.response;

import healoworld.referral_service.domain.entity.EquipmentIncident;
import healoworld.referral_service.domain.enums.EquipmentIncidentStatus;

import java.time.LocalDateTime;
import java.util.UUID;

// GET /api/v1/equipment-incidents/{incidentId} 응답
// 명세 8.2 필드 + 영향 구간(startAt, endAt)·수정 시각(updatedAt)
// completedCount는 명세 필드명 그대로이며 후속 처리 SUCCEEDED 건수를 뜻한다 (필드명 변경 여부는 팀 결정 대기)
public record EquipmentIncidentDetailResponse(UUID incidentId, UUID equipmentId, UUID institutionId, String reason,
                                              EquipmentIncidentStatus status, LocalDateTime occurredAt,
                                              LocalDateTime startAt, LocalDateTime endAt,
                                              long totalReferrals, long completedCount, long failedCount,
                                              long pendingCount, long processingCount,
                                              LocalDateTime createdAt, LocalDateTime updatedAt) {

    public static EquipmentIncidentDetailResponse of(EquipmentIncident incident, long pendingCount,
                                                     long processingCount, long completedCount, long failedCount) {
        return new EquipmentIncidentDetailResponse(incident.getId(), incident.getEquipmentId(),
                incident.getInstitutionId(), incident.getReason(), incident.getStatus(), incident.getOccurredAt(),
                incident.getStartAt(), incident.getEndAt(),
                pendingCount + processingCount + completedCount + failedCount,
                completedCount, failedCount, pendingCount, processingCount,
                incident.getCreatedAt(), incident.getUpdatedAt());
    }
}
