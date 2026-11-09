package healoworld.referral_service.presentation.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.UUID;

// GET /api/v1/equipment-incidents/summary 응답 (검사기관 장비 장애 현황)
// 세 값은 모두 같은 기관(institutionId)과 같은 기준 시각(asOf)으로 계산한다
// Referral Service에 반영된 데이터 기준이므로, 장애 이벤트(Kafka)가 늦게 반영되면 Slot Service의 원본과 잠시 다를 수 있다
public record EquipmentIncidentSummaryResponse(
        @Schema(description = "집계한 검사기관 ID") UUID institutionId,
        @Schema(description = "집계 기준 시각 (KST)") LocalDateTime asOf,
        @Schema(description = "기준 시각에 중단 구간(시작 포함, 종료 미포함)에 있는 장비 수. 실제 고장·복구 상태가 아니라 등록된 중단 구간 기준")
        long activeDowntimeEquipmentCount,
        @Schema(description = "전체 장애 이력에서 영향 대상으로 탐지된 의뢰 수 (누적, 후속 처리 상태와 무관, 의뢰 중복 제외)")
        long affectedReferralCount,
        @Schema(description = "지금 NEEDS_RESCHEDULE이고 가장 최근 차단 예약이 이 기관인 의뢰 수")
        long needsRescheduleReferralCount) {
}
