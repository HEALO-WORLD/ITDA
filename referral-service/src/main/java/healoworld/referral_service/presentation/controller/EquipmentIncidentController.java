package healoworld.referral_service.presentation.controller;

import healoworld.referral_service.application.service.EquipmentIncidentQueryService;
import healoworld.referral_service.domain.enums.RoleType;
import healoworld.referral_service.global.dto.ApiResponse;
import healoworld.referral_service.global.dto.PagedResponse;
import healoworld.referral_service.presentation.dto.request.EquipmentIncidentSearchCondition;
import healoworld.referral_service.presentation.dto.request.IncidentReferralSearchCondition;
import healoworld.referral_service.presentation.dto.response.EquipmentIncidentDetailResponse;
import healoworld.referral_service.presentation.dto.response.EquipmentIncidentResponse;
import healoworld.referral_service.presentation.dto.response.IncidentReferralResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

// 장비 장애 조회 API (운영자·검사기관 담당자용)
// 명세의 상세·영향 의뢰 조회는 /api/v1/internal/... 경로지만 Gateway가 /internal을 막으므로 외부 경로로 제공한다 (팀 확인 필요)
// X-Institution-Id·X-Role은 Gateway(JwtAuthenticationFilter)가 외부 요청의 같은 헤더를 지우고 JWT 값으로 채워 전달한다
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/equipment-incidents")
public class EquipmentIncidentController {

    private final EquipmentIncidentQueryService equipmentIncidentQueryService;

    @GetMapping
    public ResponseEntity<ApiResponse<PagedResponse<EquipmentIncidentResponse>>> getIncidents(
            @RequestHeader("X-Institution-Id") UUID institutionId,
            @RequestHeader("X-Role") RoleType role,
            @ModelAttribute EquipmentIncidentSearchCondition cond
    ) {
        PagedResponse<EquipmentIncidentResponse> response =
                equipmentIncidentQueryService.getIncidents(role, institutionId, cond);
        return ResponseEntity.ok(ApiResponse.ok("장비 장애 목록 조회가 완료되었습니다.", response));
    }

    @GetMapping("/{incidentId}")
    public ResponseEntity<ApiResponse<EquipmentIncidentDetailResponse>> getIncident(
            @RequestHeader("X-Institution-Id") UUID institutionId,
            @RequestHeader("X-Role") RoleType role,
            @PathVariable("incidentId") UUID incidentId
    ) {
        EquipmentIncidentDetailResponse response = equipmentIncidentQueryService.getIncident(role, institutionId,
                incidentId);
        return ResponseEntity.ok(ApiResponse.ok("장비 장애 조회가 완료되었습니다.", response));
    }

    @GetMapping("/{incidentId}/referrals")
    public ResponseEntity<ApiResponse<PagedResponse<IncidentReferralResponse>>> getIncidentReferrals(
            @RequestHeader("X-Institution-Id") UUID institutionId,
            @RequestHeader("X-Role") RoleType role,
            @PathVariable("incidentId") UUID incidentId,
            @ModelAttribute IncidentReferralSearchCondition cond
    ) {
        PagedResponse<IncidentReferralResponse> response =
                equipmentIncidentQueryService.getIncidentReferrals(role, institutionId, incidentId, cond);
        return ResponseEntity.ok(ApiResponse.ok("장비 장애 영향 의뢰 조회가 완료되었습니다.", response));
    }
}
