package healoworld.institution_service.presentation.controller;

import healoworld.institution_service.application.service.equipment.EquipmentExamTypeService;
import healoworld.institution_service.domain.enums.Role;
import healoworld.institution_service.global.dto.ApiResponse;
import healoworld.institution_service.global.dto.ErrorResponse;
import healoworld.institution_service.global.dto.PagedResponse;
import healoworld.institution_service.presentation.dto.request.EquipmentExamTypeCreateRequest;
import healoworld.institution_service.presentation.dto.request.EquipmentExamTypeUpdateRequest;
import healoworld.institution_service.presentation.dto.response.EquipmentExamTypeResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@Tag(name = "Equipment Exam Type", description = """
        장비별 지원 검사 유형과 검사 소요시간 관리 API (인증 필요).
        """)
@SecurityRequirement(name = "gatewayBearerAuth")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/equipments/{equipmentId}/exam-types")
public class EquipmentExamTypeController {

    private final EquipmentExamTypeService equipmentExamTypeService;

    @Operation(summary = "[EXAMINER] 장비 지원 검사 유형 등록",
            description = """
                    장비가 지원하는 검사 유형과 소요시간을 등록합니다. 장비와 검사 유형의 방식(CT/MRI)이 일치해야 합니다.
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "등록 완료"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = """
                    - VALIDATION_FAILED: examTypeId 누락, durationMinutes 누락 또는 1 미만
                    - MISSING_HEADER: X-User-Id·X-Institution-Id·X-Role 헤더 누락 (직접 호출 시)
                    - INVALID_INPUT: UUID·역할 값 형식 오류, 요청 본문 형식 오류, 장비 종류와 검사 방식이 다름
                    """, content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "FORBIDDEN: EXAMINER가 아님, 자기 기관 장비가 아님, 비활성 기관 또는 검사기관이 아님",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "RESOURCE_NOT_FOUND: 장비·기관·검사 유형이 없거나 삭제됨",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "DUPLICATE_EQUIPMENT_EXAM_TYPE: 이미 등록된 장비·검사 유형 연결",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping
    public ResponseEntity<ApiResponse<EquipmentExamTypeResponse>> createEquipmentExamType(
            @Parameter(description = "장비 ID") @PathVariable("equipmentId") UUID equipmentId,
            @Parameter(description = "요청자 ID") @RequestHeader("X-User-Id") UUID userId,
            @Parameter(description = "요청자 소속 기관 ID") @RequestHeader("X-Institution-Id") UUID userInstitutionId,
            @Parameter(description = "요청자 권한") @RequestHeader("X-Role") Role userRole,
            @Valid @RequestBody EquipmentExamTypeCreateRequest request
    ) {
        EquipmentExamTypeResponse response = equipmentExamTypeService.createEquipmentExamType(
                equipmentId,
                request,
                userId,
                userInstitutionId,
                userRole
        );

        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok("장비의 지원 검사 유형이 등록되었습니다.", response));
    }

    @Operation(summary = "[EXAMINER] 장비별 검사 소요시간 수정",
            description = """
                    장비별 검사 소요시간을 수정합니다. 이미 생성된 슬롯에는 반영되지 않습니다.
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "수정 완료"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = """
                    - VALIDATION_FAILED: durationMinutes 누락 또는 1 미만
                    - MISSING_HEADER: X-User-Id·X-Institution-Id·X-Role 헤더 누락 (직접 호출 시)
                    - INVALID_INPUT: UUID·역할 값 형식 오류 또는 요청 본문 형식 오류
                    """, content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "FORBIDDEN: EXAMINER가 아님, 자기 기관 장비가 아님, 비활성 기관 또는 검사기관이 아님",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "RESOURCE_NOT_FOUND: 장비·기관·검사 유형이 없거나, 장비에 등록되지 않은 검사 유형",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PatchMapping("/{examTypeId}")
    public ResponseEntity<ApiResponse<EquipmentExamTypeResponse>> updateEquipmentExamType(
            @Parameter(description = "장비 ID") @PathVariable("equipmentId") UUID equipmentId,
            @Parameter(description = "검사 유형 ID") @PathVariable("examTypeId") UUID examTypeId,
            @Parameter(description = "요청자 ID") @RequestHeader("X-User-Id") UUID userId,
            @Parameter(description = "요청자 소속 기관 ID") @RequestHeader("X-Institution-Id") UUID userInstitutionId,
            @Parameter(description = "요청자 권한") @RequestHeader("X-Role") Role userRole,
            @Valid @RequestBody EquipmentExamTypeUpdateRequest request
    ) {
        EquipmentExamTypeResponse response = equipmentExamTypeService.updateEquipmentExamType(
                equipmentId, examTypeId, request, userId, userInstitutionId, userRole
        );

        return ResponseEntity.ok(ApiResponse.ok("장비별 검사 소요시간이 수정되었습니다.", response));
    }

    @Operation(summary = "장비 지원 검사 유형 목록 조회",
            description = """
                    장비의 지원 검사 유형과 소요시간을 조회합니다. 자기 기관 또는 활성 협약 기관의 장비만 조회할 수 있습니다.
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = """
                    - MISSING_HEADER: X-User-Id·X-Institution-Id·X-Role 헤더 누락 (직접 호출 시)
                    - INVALID_INPUT: UUID·역할 값 형식 오류, page < 0, size가 1~100 범위 밖
                    """, content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "FORBIDDEN: 허용되지 않은 역할, 비활성 기관, 자기 기관·활성 협약 기관의 장비가 아님",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "RESOURCE_NOT_FOUND: 장비 또는 관련 기관이 없거나 삭제됨",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping
    public ResponseEntity<ApiResponse<PagedResponse<EquipmentExamTypeResponse>>> getEquipmentExamTypes(
            @Parameter(description = "장비 ID") @PathVariable("equipmentId") UUID equipmentId,
            @Parameter(description = "요청자 ID") @RequestHeader("X-User-Id") UUID userId,
            @Parameter(description = "요청자 소속 기관 ID") @RequestHeader("X-Institution-Id") UUID userInstitutionId,
            @Parameter(description = "요청자 권한") @RequestHeader("X-Role") Role userRole,
            @Parameter(description = "페이지 번호 (0부터)") @RequestParam(name = "page", defaultValue = "0") Integer page,
            @Parameter(description = "페이지 크기 (1~100)") @RequestParam(name = "size", defaultValue = "20") Integer size
    ) {
        PagedResponse<EquipmentExamTypeResponse> response = equipmentExamTypeService.getEquipmentExamTypes(
                equipmentId,
                userId,
                userInstitutionId,
                userRole,
                page,
                size
        );

        return ResponseEntity.ok(ApiResponse.ok("장비의 지원 검사 유형 목록 조회에 성공했습니다.", response));
    }
}
