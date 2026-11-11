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
import healoworld.slot_service.global.dto.ErrorResponse;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;

import java.util.UUID;

// 게이트웨이 정책의 [EXAMINER, ADMIN] 그룹(등록·복구·연장)과 대응한다
@Tag(name = "Equipment Downtime", description = "장비 중단 API")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/equipment-downtimes")
public class EquipmentDowntimeController {

    private final EquipmentDowntimeService equipmentDowntimeService;

    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "201",
                    description = "등록 완료"
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "VALIDATION_FAILED, DOWNTIME_INVALID_TIME_RANGE, DOWNTIME_END_NOT_FUTURE, MISSING_HEADER, INVALID_INPUT",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "403",
                    description = "FORBIDDEN",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "404",
                    description = "RESOURCE_NOT_FOUND",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "409",
                    description = "DOWNTIME_OVERLAP",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "502",
                    description = "REFERENCE_RESPONSE_INVALID",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "503",
                    description = "REFERENCE_SERVICE_UNAVAILABLE",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))
            )
    })
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