package healoworld.referral_service.presentation.controller;

import healoworld.referral_service.application.service.EquipmentIncidentQueryService;
import healoworld.referral_service.domain.enums.RoleType;
import healoworld.referral_service.global.dto.ApiResponse;
import healoworld.referral_service.global.dto.PagedResponse;
import healoworld.referral_service.presentation.dto.request.EquipmentIncidentSearchCondition;
import healoworld.referral_service.presentation.dto.request.IncidentReferralSearchCondition;
import healoworld.referral_service.presentation.dto.response.EquipmentIncidentDetailResponse;
import healoworld.referral_service.presentation.dto.response.EquipmentIncidentResponse;
import healoworld.referral_service.presentation.dto.response.EquipmentIncidentSummaryResponse;
import healoworld.referral_service.presentation.dto.response.IncidentReferralResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

// 장비 장애 조회 API (운영자·검사기관 담당자용)
// 명세의 상세·영향 의뢰 조회는 /api/v1/internal/... 경로지만 Gateway가 /internal을 막으므로 외부 경로로 제공한다 (팀 확인 필요)
// X-Institution-Id·X-Role은 Gateway(JwtAuthenticationFilter)가 외부 요청의 같은 헤더를 지우고 JWT 값으로 채워 전달한다
@Tag(name = "Equipment Incident", description = "장비 장애 조회 API")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/equipment-incidents")
public class EquipmentIncidentController {

    private final EquipmentIncidentQueryService equipmentIncidentQueryService;

    @Operation(summary = "장비 장애 목록 조회",
            description = """
                    장비 장애를 최신 발생순으로 조회합니다. 장비 ID와 장애 상태로 필터링할 수 있습니다.

                    - ADMIN: 모든 기관의 장애를 조회합니다. institutionId를 넣으면 해당 기관으로 좁힙니다.
                    - EXAMINER: 자기 기관의 장애만 조회합니다. 요청의 institutionId는 무시됩니다.
                    - REQUESTER: 조회할 수 없습니다.

                    - 400 MISSING_HEADER: 필수 헤더 누락
                    - 400 INVALID_INPUT: 헤더의 형식 또는 역할 값이 잘못됨
                    - 400 VALIDATION_FAILED: 검색 조건의 UUID·상태 값 형식이 잘못됨
                    - 403 EQUIPMENT_INCIDENT_ACCESS_DENIED: REQUESTER 요청
                    """)
    @GetMapping
    public ResponseEntity<ApiResponse<PagedResponse<EquipmentIncidentResponse>>> getIncidents(
            @Parameter(description = "요청자 소속 기관 ID") @RequestHeader("X-Institution-Id") UUID institutionId,
            @Parameter(description = "요청자 권한") @RequestHeader("X-Role") RoleType role,
            @ParameterObject @ModelAttribute EquipmentIncidentSearchCondition cond
    ) {
        PagedResponse<EquipmentIncidentResponse> response =
                equipmentIncidentQueryService.getIncidents(role, institutionId, cond);
        return ResponseEntity.ok(ApiResponse.ok("장비 장애 목록 조회가 완료되었습니다.", response));
    }

    @Operation(summary = "장비 장애 현황 조회",
            description = """
                    검사기관의 장비 장애 현황을 집계합니다. 세 값은 같은 기관과 같은 기준 시각(asOf)으로 계산합니다.

                    - activeDowntimeEquipmentCount: 기준 시각에 중단 구간(시작 포함, 종료 미포함)에 있는 장비 수.
                      실제 고장·복구 상태가 아니라 등록된 중단 구간 기준입니다.
                    - affectedReferralCount: 전체 장애 이력에서 영향 대상으로 탐지된 의뢰 수 (누적, 후속 처리 상태와 무관)
                    - needsRescheduleReferralCount: 지금 재조정이 필요하고(NEEDS_RESCHEDULE) 가장 최근 차단 예약이 이 기관인 의뢰 수
                    - 장애 이벤트(Kafka)가 늦게 반영되면 Slot Service의 원본과 잠시 다를 수 있습니다.
                    - 복구 완료 장애 수는 복구 데이터가 없어 제공하지 않습니다.

                    - ADMIN: institutionId로 조회할 기관을 반드시 지정합니다.
                    - EXAMINER: 자기 기관만 조회합니다. 요청의 institutionId는 무시됩니다.
                    - REQUESTER: 조회할 수 없습니다.

                    - 400 MISSING_HEADER: 필수 헤더 누락
                    - 400 INVALID_INPUT: 헤더 또는 institutionId의 형식이 잘못됨
                    - 400 VALIDATION_FAILED: ADMIN 요청에 institutionId가 없음
                    - 403 EQUIPMENT_INCIDENT_ACCESS_DENIED: REQUESTER 요청
                    """)
    @GetMapping("/summary")
    public ResponseEntity<ApiResponse<EquipmentIncidentSummaryResponse>> getSummary(
            @Parameter(description = "요청자 소속 기관 ID") @RequestHeader("X-Institution-Id") UUID institutionId,
            @Parameter(description = "요청자 권한") @RequestHeader("X-Role") RoleType role,
            @Parameter(description = "조회할 기관 ID (ADMIN 필수, EXAMINER는 무시)")
            @RequestParam(value = "institutionId", required = false) UUID requestedInstitutionId
    ) {
        EquipmentIncidentSummaryResponse response =
                equipmentIncidentQueryService.getSummary(role, institutionId, requestedInstitutionId);
        return ResponseEntity.ok(ApiResponse.ok("장비 장애 현황 조회가 완료되었습니다.", response));
    }

    @Operation(summary = "장비 장애 상세 조회",
            description = """
                    장애 정보와 의뢰별 후속 처리 상태별 건수를 조회합니다.

                    - ADMIN: 모든 기관의 장애를 조회합니다.
                    - EXAMINER: 자기 기관의 장애만 조회합니다.
                    - REQUESTER: 조회할 수 없습니다.

                    - 400 MISSING_HEADER: 필수 헤더 누락
                    - 400 INVALID_INPUT: 헤더 또는 장애 ID의 형식이 잘못됨
                    - 403 EQUIPMENT_INCIDENT_ACCESS_DENIED: REQUESTER 요청
                    - 404 EQUIPMENT_INCIDENT_NOT_FOUND: 장애가 없거나, EXAMINER가 다른 기관의 장애를 요청함
                    """)
    @GetMapping("/{incidentId}")
    public ResponseEntity<ApiResponse<EquipmentIncidentDetailResponse>> getIncident(
            @Parameter(description = "요청자 소속 기관 ID") @RequestHeader("X-Institution-Id") UUID institutionId,
            @Parameter(description = "요청자 권한") @RequestHeader("X-Role") RoleType role,
            @Parameter(description = "장애 ID") @PathVariable("incidentId") UUID incidentId
    ) {
        EquipmentIncidentDetailResponse response = equipmentIncidentQueryService.getIncident(role, institutionId,
                incidentId);
        return ResponseEntity.ok(ApiResponse.ok("장비 장애 조회가 완료되었습니다.", response));
    }

    @Operation(summary = "장애 영향 의뢰 처리 현황 조회",
            description = """
                    장애의 영향을 받은 예약·의뢰별 후속 처리 현황을 작업 생성순으로 조회합니다.
                    처리 상태로 필터링할 수 있습니다.

                    - ADMIN: 모든 기관의 장애를 조회하며 lastError 값을 받습니다.
                    - EXAMINER: 자기 기관의 장애만 조회하며 lastError는 항상 null입니다. 다른 처리 현황 필드는 ADMIN과 같습니다.
                    - REQUESTER: 조회할 수 없습니다.

                    - 400 MISSING_HEADER: 필수 헤더 누락
                    - 400 INVALID_INPUT: 헤더 또는 장애 ID의 형식이 잘못됨
                    - 400 VALIDATION_FAILED: 검색 조건의 처리 상태 값이 잘못됨
                    - 403 EQUIPMENT_INCIDENT_ACCESS_DENIED: REQUESTER 요청
                    - 404 EQUIPMENT_INCIDENT_NOT_FOUND: 장애가 없거나, EXAMINER가 다른 기관의 장애를 요청함
                    """)
    @GetMapping("/{incidentId}/referrals")
    public ResponseEntity<ApiResponse<PagedResponse<IncidentReferralResponse>>> getIncidentReferrals(
            @Parameter(description = "요청자 소속 기관 ID") @RequestHeader("X-Institution-Id") UUID institutionId,
            @Parameter(description = "요청자 권한") @RequestHeader("X-Role") RoleType role,
            @Parameter(description = "장애 ID") @PathVariable("incidentId") UUID incidentId,
            @ParameterObject @ModelAttribute IncidentReferralSearchCondition cond
    ) {
        PagedResponse<IncidentReferralResponse> response =
                equipmentIncidentQueryService.getIncidentReferrals(role, institutionId, incidentId, cond);
        return ResponseEntity.ok(ApiResponse.ok("장비 장애 영향 의뢰 조회가 완료되었습니다.", response));
    }
}
