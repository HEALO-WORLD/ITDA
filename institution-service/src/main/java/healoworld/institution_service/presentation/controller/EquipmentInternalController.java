package healoworld.institution_service.presentation.controller;

import healoworld.institution_service.application.service.equipment.EquipmentOwnerService;
import healoworld.institution_service.application.service.equipment.EquipmentSlotContextService;
import healoworld.institution_service.application.service.equipment.EquipmentVisibilityService;
import healoworld.institution_service.domain.enums.Role;
import healoworld.institution_service.global.dto.ApiResponse;
import healoworld.institution_service.global.dto.ErrorResponse;
import healoworld.institution_service.presentation.dto.response.EquipmentOwnerResponse;
import healoworld.institution_service.presentation.dto.response.EquipmentSlotContextResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

@Tag(name = "Equipment (Internal)", description = """
        슬롯 처리에 필요한 내부 장비 API. Gateway 경유 호출은 불가합니다.
        """)
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/internal/equipments")
public class EquipmentInternalController {

    private final EquipmentSlotContextService slotContextService;
    private final EquipmentVisibilityService equipmentVisibilityService;
    private final EquipmentOwnerService equipmentOwnerService;

    @Operation(summary = "[내부 전용] 슬롯 생성 기준정보 조회",
            description = """
                    슬롯 생성에 필요한 장비·검사 유형·운영시간 정보를 조회합니다. 정상 장비와 활성 검사기관에 한해 유효합니다.
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = """
                    - INVALID_INPUT: UUID·날짜(yyyy-MM-dd) 형식 오류, 장비 종류와 검사 방식이 다름
                    - UNSUPPORTED_REQUEST: 필수 쿼리 파라미터 누락
                    """, content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "RESOURCE_NOT_FOUND: 장비·기관·검사 유형이 없거나, 장비에 등록되지 않은 검사 유형",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = """
                    - EQUIPMENT_NOT_AVAILABLE: 비활성 기관, 검사기관이 아님, 장비가 OPERATIONAL이 아님, 검사 소요시간이 올바르지 않음
                    - OPERATING_HOURS_NOT_AVAILABLE: 해당 요일에 운영시간이 없음
                    """, content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/{equipmentId}/slot-context")
    public ResponseEntity<ApiResponse<EquipmentSlotContextResponse>> getSlotContext(
            @Parameter(description = "장비 ID") @PathVariable("equipmentId") UUID equipmentId,
            @Parameter(description = "검사 유형 ID") @RequestParam("examTypeId") UUID examTypeId,
            @Parameter(description = "슬롯 날짜 (yyyy-MM-dd)") @RequestParam("date")
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date
    ) {
        EquipmentSlotContextResponse response = slotContextService.getSlotContext(equipmentId, examTypeId, date);

        return ResponseEntity.ok(ApiResponse.ok("슬롯 생성 기준정보 조회에 성공했습니다.", response));
    }

    // 장비의 실제 소속 기관 조회. Gateway의 block-internal 라우트가 /api/v1/internal/** 외부 접근을 막는다
    @Operation(summary = "[내부 전용] 장비 소속 기관 조회",
            description = """
                    장비 소속 기관 ID를 조회합니다. 장비 운영 상태와 무관하게 조회합니다.
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "INVALID_INPUT: equipmentId가 UUID 형식이 아님",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "RESOURCE_NOT_FOUND: 장비가 없거나 삭제됨",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/{equipmentId}/owner")
    public ResponseEntity<ApiResponse<EquipmentOwnerResponse>> getOwner(
            @Parameter(description = "장비 ID") @PathVariable("equipmentId") UUID equipmentId) {
        return ResponseEntity.ok(ApiResponse.ok("장비 소속 기관 조회에 성공했습니다.", equipmentOwnerService.getOwner(equipmentId)));
    }

    @Operation(summary = "[내부 전용] 조회 가능한 장비 ID 목록 조회",
            description = """
                    역할과 협약 관계에 따라 조회 가능한 장비 ID를 반환합니다. 기본적으로 정상 장비만 포함하며 의뢰기관은 비정상 장비를 조회할 수 없습니다.
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공 (data는 장비 ID 집합, 없으면 빈 배열)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = """
                    - INVALID_INPUT: UUID·역할·boolean 값 형식 오류
                    - UNSUPPORTED_REQUEST: 필수 쿼리 파라미터 누락
                    """, content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "FORBIDDEN: REQUESTER가 onlyOperational=false로 요청, 요청자 기관이 비활성이거나 역할과 기관 유형이 맞지 않음",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "RESOURCE_NOT_FOUND: ADMIN이 아닌 요청자의 기관이 없거나 삭제됨",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/visible")
    public ResponseEntity<ApiResponse<Set<UUID>>> getVisibleEquipmentIds(
            @Parameter(description = "요청자 소속 기관 ID") @RequestParam("callerInstitutionId") UUID callerInstitutionId,
            @Parameter(description = "요청자 권한") @RequestParam("role") Role role,
            @Parameter(description = "장비 소속 기관 필터 (생략 시 전체)") @RequestParam(name = "institutionId", required = false)
            UUID institutionId,
            @Parameter(description = "OPERATIONAL 장비만 조회할지 여부 (기본 true)") @RequestParam(name = "onlyOperational", defaultValue = "true")
            boolean onlyOperational
    ) {
        Set<UUID> ids = equipmentVisibilityService.getVisibleEquipmentIds(
                callerInstitutionId,
                role,
                institutionId,
                onlyOperational
        );

        return ResponseEntity.ok(
                ApiResponse.ok("조회 가능한 장비 ID 조회에 성공했습니다.", ids)
        );
    }
}