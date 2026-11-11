package healoworld.slot_service.presentation.controller;

import healoworld.slot_service.application.service.EquipmentDowntimeService;
import healoworld.slot_service.global.dto.ApiResponse;
import healoworld.slot_service.global.dto.ErrorResponse;
import healoworld.slot_service.presentation.dto.request.EquipmentDowntimeCreateRequest;
import healoworld.slot_service.presentation.dto.response.EquipmentDowntimeCreateResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

// 게이트웨이 정책의 [EXAMINER, ADMIN] 그룹(POST /api/v1/equipment-downtimes)과 대응한다
@Tag(name = "Equipment Downtime", description = "장비 중단 API")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/equipment-downtimes")
public class EquipmentDowntimeController {

    private final EquipmentDowntimeService equipmentDowntimeService;

    @Operation(
            summary = "장비 중단 등록",
            description = """
                    장비 중단 시간을 등록하고 겹치는 가용 슬롯을 UNAVAILABLE로 변경합니다. 선점·확정 슬롯은 충돌 목록에 포함하며 영향 예약의 후속 처리는 Kafka 이벤트로 진행합니다.
                    """
    )
    // 코드별 사유는 위 description의 "오류 응답" 목록에 있다. 여기서는 상태 코드와 ErrorResponse 구조만 연결한다
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "등록 완료"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "VALIDATION_FAILED, DOWNTIME_INVALID_TIME_RANGE, DOWNTIME_END_NOT_FUTURE, MISSING_HEADER, INVALID_INPUT",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "FORBIDDEN",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "RESOURCE_NOT_FOUND",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "DOWNTIME_OVERLAP",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "502", description = "REFERENCE_RESPONSE_INVALID",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "503", description = "REFERENCE_SERVICE_UNAVAILABLE",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
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
}
