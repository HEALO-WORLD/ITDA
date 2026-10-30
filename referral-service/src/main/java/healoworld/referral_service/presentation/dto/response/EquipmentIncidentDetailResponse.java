package healoworld.referral_service.presentation.dto.response;

import healoworld.referral_service.domain.entity.EquipmentIncident;
import healoworld.referral_service.domain.enums.EquipmentIncidentStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.UUID;

// GET /api/v1/equipment-incidents/{incidentId} 응답
// 명세 8.2 필드 + 영향 구간(startAt, endAt)·수정 시각(updatedAt)
// completedCount는 명세 필드명 그대로이며 후속 처리 SUCCEEDED 건수를 뜻한다 (필드명 변경 여부는 팀 결정 대기)
public record EquipmentIncidentDetailResponse(
        @Schema(description = "장애 ID") UUID incidentId,
        @Schema(description = "장비 ID") UUID equipmentId,
        @Schema(description = "기관 ID") UUID institutionId,
        @Schema(description = "장애 사유") String reason,
        @Schema(description = "장애 상태") EquipmentIncidentStatus status,
        @Schema(description = "장애 발생 시각") LocalDateTime occurredAt,
        @Schema(description = "장애 영향 시작 시각") LocalDateTime startAt,
        @Schema(description = "장애 영향 종료 시각") LocalDateTime endAt,
        @Schema(description = "후속 처리 작업 전체 건수") long totalReferrals,
        @Schema(description = "후속 처리 SUCCEEDED 건수") long completedCount,
        @Schema(description = "후속 처리 FAILED 건수") long failedCount,
        @Schema(description = "후속 처리 PENDING 건수") long pendingCount,
        @Schema(description = "후속 처리 PROCESSING 건수") long processingCount,
        @Schema(description = "장애 등록 시각") LocalDateTime createdAt,
        @Schema(description = "장애 수정 시각") LocalDateTime updatedAt) {

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
