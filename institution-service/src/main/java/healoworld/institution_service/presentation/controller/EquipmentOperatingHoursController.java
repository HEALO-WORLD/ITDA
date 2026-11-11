package healoworld.institution_service.presentation.controller;

import healoworld.institution_service.application.service.equipment.EquipmentOperatingHoursService;
import healoworld.institution_service.domain.enums.Role;
import healoworld.institution_service.global.dto.ApiResponse;
import healoworld.institution_service.global.dto.ErrorResponse;
import healoworld.institution_service.presentation.dto.request.EquipmentOperatingHoursUpdateRequest;
import healoworld.institution_service.presentation.dto.response.EquipmentOperatingHoursResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@Tag(name = "Equipment Operating Hours", description = """
        장비별 요일 운영시간 조회·설정 API (인증 필요).
        """)
@SecurityRequirement(name = "gatewayBearerAuth")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/equipments/{equipmentId}/operating-hours")
public class EquipmentOperatingHoursController {

    private final EquipmentOperatingHoursService operatingHoursService;

    @Operation(summary = "장비 운영시간 조회",
            description = """
                    장비의 요일별 운영시간을 월요일부터 순서대로 조회합니다. 미운영 요일은 제외합니다.
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = """
                    - MISSING_HEADER: X-User-Id·X-Institution-Id·X-Role 헤더 누락 (직접 호출 시)
                    - INVALID_INPUT: UUID·역할 값 형식 오류
                    """, content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "FORBIDDEN: 허용되지 않은 역할, 비활성 기관, 자기 기관·활성 협약 기관의 장비가 아님",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "RESOURCE_NOT_FOUND: 장비 또는 관련 기관이 없거나 삭제됨",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping
    public ResponseEntity<ApiResponse<List<EquipmentOperatingHoursResponse>>> getOperatingHours(
            @Parameter(description = "장비 ID") @PathVariable("equipmentId") UUID equipmentId,
            @Parameter(description = "요청자 ID") @RequestHeader("X-User-Id") UUID userId,
            @Parameter(description = "요청자 소속 기관 ID") @RequestHeader("X-Institution-Id") UUID userInstitutionId,
            @Parameter(description = "요청자 권한") @RequestHeader("X-Role") Role userRole
    ) {
        List<EquipmentOperatingHoursResponse> response = operatingHoursService.getOperatingHours(
                equipmentId,
                userId,
                userInstitutionId,
                userRole
        );

        return ResponseEntity.ok(ApiResponse.ok("장비 운영시간 조회에 성공했습니다.", response));
    }

    @Operation(summary = "[EXAMINER] 장비 운영시간 설정 (전체 교체)",
            description = """
                    요일별 운영시간을 요청 목록으로 전체 교체합니다. 빠진 요일은 미운영으로 처리하며 기존 슬롯은 변경하지 않습니다.
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "설정 완료"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = """
                    - VALIDATION_FAILED: operatingHours 누락 또는 7개 초과, week가 1~7 밖, 시간 누락
                    - MISSING_HEADER: X-User-Id·X-Institution-Id·X-Role 헤더 누락 (직접 호출 시)
                    - INVALID_INPUT: UUID·역할·시간 형식 오류, 요청 본문 형식 오류, 같은 요일 중복, startTime이 endTime보다 앞이 아님
                    """, content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "FORBIDDEN: EXAMINER가 아님, 자기 기관 장비가 아님, 비활성 기관 또는 검사기관이 아님",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "RESOURCE_NOT_FOUND: 장비 또는 기관이 없거나 삭제됨",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PutMapping
    public ResponseEntity<ApiResponse<List<EquipmentOperatingHoursResponse>>> updateOperatingHours(
            @Parameter(description = "장비 ID") @PathVariable("equipmentId") UUID equipmentId,
            @Parameter(description = "요청자 ID") @RequestHeader("X-User-Id") UUID userId,
            @Parameter(description = "요청자 소속 기관 ID") @RequestHeader("X-Institution-Id") UUID userInstitutionId,
            @Parameter(description = "요청자 권한") @RequestHeader("X-Role") Role userRole,
            @Valid @RequestBody EquipmentOperatingHoursUpdateRequest request
    ) {
        List<EquipmentOperatingHoursResponse> response = operatingHoursService.updateOperatingHours(
                equipmentId,
                request,
                userId,
                userInstitutionId,
                userRole
        );

        return ResponseEntity.ok(ApiResponse.ok("장비 운영시간이 설정되었습니다.", response));
    }
}