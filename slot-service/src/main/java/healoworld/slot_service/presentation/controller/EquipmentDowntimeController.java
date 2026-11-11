package healoworld.slot_service.presentation.controller;

import healoworld.slot_service.application.service.EquipmentDowntimeService;
import healoworld.slot_service.global.dto.ApiResponse;
import healoworld.slot_service.presentation.dto.request.EquipmentDowntimeCreateRequest;
import healoworld.slot_service.presentation.dto.response.EquipmentDowntimeCreateResponse;
import healoworld.slot_service.presentation.dto.response.EquipmentDowntimeLifecycleResponse;
import healoworld.slot_service.presentation.dto.request.EquipmentDowntimeRecoveryRequest;
import healoworld.slot_service.presentation.dto.request.EquipmentDowntimeExtensionRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

// 게이트웨이 정책의 [EXAMINER, ADMIN] 그룹(등록·복구·연장)과 대응한다
@Tag(name = "Equipment Downtime", description = "장비 중단 API")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/equipment-downtimes")
public class EquipmentDowntimeController {

    private final EquipmentDowntimeService equipmentDowntimeService;

    @Operation(
            summary = "장비 중단 등록",
            description = """
                    장비의 중단 구간을 등록합니다. downtimeType은 MAINTENANCE 또는 FAILURE입니다.
                    미래 일정은 해당 기간의 슬롯을 먼저 차단하고, 장비 상태는 시작 시각에 변경합니다.
                    종료 예정 시각만으로 정상 운영으로 복귀하지 않으며 별도 복구 확인이 필요합니다.

                    - EXAMINER는 자기 기관 장비만, ADMIN은 모든 장비를 등록할 수 있습니다.
                    - 시간은 오프셋을 포함해 보내며 한국 시간 기준으로 저장합니다.
                    - 시작 일시는 과거여도 되지만(이미 발생한 장애), 종료 일시는 현재 이후여야 합니다.
                    - 중단 구간과 겹치는 AVAILABLE 슬롯은 UNAVAILABLE로 바뀝니다(affectedSlotCount).
                      경계만 맞닿는 슬롯은 겹치지 않는 것으로 봅니다.
                    - HELD·BOOKED 슬롯은 상태와 예약을 유지한 채 conflictedSlots로 반환합니다.
                      중단 표시가 있는 HELD 슬롯은 새로 확정할 수 없으며, 해제·만료되면 UNAVAILABLE이 됩니다.
                    - 예약·의뢰 상태는 바꾸지 않습니다. 등록과 같은 트랜잭션에서 EquipmentDowntimeStarted 이벤트를 Outbox에 저장하고,
                      커밋 후 equipment-events 토픽으로 발행합니다. 이벤트의 기관 ID는 장비의 실제 소속 기관입니다.

                    오류 응답:
                    - 400 VALIDATION_FAILED: 필수 값 누락, 사유가 비었거나 500자 초과
                    - 400 DOWNTIME_INVALID_TIME_RANGE: 종료 일시가 시작 일시 이후가 아님
                    - 400 DOWNTIME_END_NOT_FUTURE: 종료 일시가 현재 이후가 아님
                    - 409 DOWNTIME_OVERLAP: 같은 장비에 기간이 겹치는 중단이 이미 있음
                    - 400 MISSING_HEADER: 사용자·기관·권한 헤더 누락
                    - 400 INVALID_INPUT: 잘못된 UUID 또는 요청 본문
                    - 403 FORBIDDEN: EXAMINER·ADMIN이 아니거나, EXAMINER가 자기 기관 장비가 아닌 장비를 지정
                    - 404 RESOURCE_NOT_FOUND: ADMIN이 지정한 장비가 없음
                    - 502 REFERENCE_RESPONSE_INVALID: Institution Service 응답이 올바르지 않음
                    - 503 REFERENCE_SERVICE_UNAVAILABLE: Institution Service 응답 없음
                    """
    )
    @PostMapping
    public ResponseEntity<ApiResponse<EquipmentDowntimeCreateResponse>> createDowntime(
            @Parameter(description = "요청자 ID") @RequestHeader("X-User-Id") UUID userId,
            @Parameter(description = "요청자 기관 ID") @RequestHeader("X-Institution-Id") UUID userInstitutionId,
            @Parameter(description = "요청자 권한") @RequestHeader("X-Role") String userRole,
            @Valid @RequestBody EquipmentDowntimeCreateRequest request
    ) {
        EquipmentDowntimeCreateResponse response = equipmentDowntimeService.createDowntime(
                request,
                userId,
                userInstitutionId,
                userRole
        );

        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok("장비 중단이 등록되었습니다.", response));
    }
    @Operation(summary = "장비 복구 확인", description = "복구 확인 전에는 예정 종료 이후에도 운영 중단을 유지합니다. 기존 예약·의뢰는 자동 복원하지 않습니다.")
    @PatchMapping("/{downtimeId}/recovery")
    public ResponseEntity<ApiResponse<EquipmentDowntimeLifecycleResponse>> recover(
            @PathVariable UUID downtimeId,
            @RequestHeader("X-User-Id") UUID userId,
            @RequestHeader("X-Institution-Id") UUID institutionId,
            @RequestHeader("X-Role") String role,
            @Valid @RequestBody EquipmentDowntimeRecoveryRequest request) {
        return ResponseEntity.ok(ApiResponse.ok("장비 복구가 확인되었습니다.",
                equipmentDowntimeService.recover(downtimeId, request.reason(), userId, institutionId, role)));
    }

    @Operation(summary = "장비 중단 기간 연장", description = "미복구 중단만 연장합니다. 추가 구간의 슬롯을 차단하고 예약 재조정 이벤트를 저장합니다.")
    @PatchMapping("/{downtimeId}/extension")
    public ResponseEntity<ApiResponse<EquipmentDowntimeLifecycleResponse>> extend(
            @PathVariable UUID downtimeId,
            @RequestHeader("X-User-Id") UUID userId,
            @RequestHeader("X-Institution-Id") UUID institutionId,
            @RequestHeader("X-Role") String role,
            @Valid @RequestBody EquipmentDowntimeExtensionRequest request) {
        return ResponseEntity.ok(ApiResponse.ok("장비 중단 기간이 연장되었습니다.",
                equipmentDowntimeService.extend(downtimeId, request.endedAt(), userId, institutionId, role)));
    }
}

