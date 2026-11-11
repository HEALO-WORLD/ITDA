package healoworld.institution_service.presentation.controller;

import healoworld.institution_service.application.service.equipment.EquipmentService;
import healoworld.institution_service.domain.enums.Modality;
import healoworld.institution_service.domain.enums.Role;
import healoworld.institution_service.global.dto.ApiResponse;
import healoworld.institution_service.global.dto.ErrorResponse;
import healoworld.institution_service.global.dto.PagedResponse;
import healoworld.institution_service.presentation.dto.request.EquipmentCreateRequest;
import healoworld.institution_service.presentation.dto.request.EquipmentUpdateRequest;
import healoworld.institution_service.presentation.dto.response.EquipmentCreateResponse;
import healoworld.institution_service.presentation.dto.response.EquipmentDetailResponse;
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

@Tag(name = "Equipment", description = """
        검사 장비 등록·조회·수정 API (인증 필요).
        """)
@SecurityRequirement(name = "gatewayBearerAuth")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1")
public class EquipmentController {

    private final EquipmentService equipmentService;

    @Operation(summary = "[EXAMINER] 장비 등록",
            description = """
                    검사기관에 장비를 등록합니다. 정상 상태로 시작하며 자기 기관에 같은 이름의 장비를 중복 등록할 수 없습니다.
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "등록 완료"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = """
                    - VALIDATION_FAILED: 장비명(최대 50자)·장비 종류 누락 또는 형식 오류
                    - MISSING_HEADER: X-User-Id·X-Institution-Id·X-Role 헤더 누락 (직접 호출 시)
                    - INVALID_INPUT: UUID·역할·장비 종류 값 형식 오류 또는 요청 본문 형식 오류
                    """, content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "FORBIDDEN: EXAMINER가 아니거나 자기 기관이 아닌 기관에 등록",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "RESOURCE_NOT_FOUND: 기관이 없거나 삭제됨",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = """
                    - INSTITUTION_NOT_AVAILABLE: 비활성 기관이거나 검사기관이 아님
                    - DUPLICATE_EQUIPMENT_NAME: 같은 기관에 같은 이름의 장비가 있음
                    """, content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/institutions/{institutionId}/equipments")
    public ResponseEntity<ApiResponse<EquipmentCreateResponse>> createEquipment(
            @Parameter(description = "장비를 등록할 기관 ID (자기 기관)") @PathVariable("institutionId") UUID institutionId,
            @Parameter(description = "요청자 ID") @RequestHeader("X-User-Id") UUID userId,
            @Parameter(description = "요청자 소속 기관 ID") @RequestHeader("X-Institution-Id") UUID userInstitutionId,
            @Parameter(description = "요청자 권한") @RequestHeader("X-Role") Role userRole,
            @Valid @RequestBody EquipmentCreateRequest request
    ) {
        EquipmentCreateResponse response = equipmentService.createEquipment(
                institutionId,
                request,
                userId,
                userInstitutionId,
                userRole
        );

        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok("장비가 등록되었습니다.", response));}

    @Operation(summary = "장비 단건 조회",
            description = """
                    장비 정보를 조회합니다. 검사기관은 자기 장비, 의뢰기관은 활성 협약 검사기관의 장비만 조회할 수 있습니다.
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
    @GetMapping("/equipments/{equipmentId}")
    public ResponseEntity<ApiResponse<EquipmentDetailResponse>> getEquipment(
            @Parameter(description = "장비 ID") @PathVariable("equipmentId") UUID equipmentId,
            @Parameter(description = "요청자 ID") @RequestHeader("X-User-Id") UUID userId,
            @Parameter(description = "요청자 소속 기관 ID") @RequestHeader("X-Institution-Id") UUID userInstitutionId,
            @Parameter(description = "요청자 권한") @RequestHeader("X-Role") Role userRole
    ) {
        EquipmentDetailResponse response = equipmentService.getEquipment(
                equipmentId,
                userId,
                userInstitutionId,
                userRole
        );

        return ResponseEntity.ok(ApiResponse.ok("장비 조회에 성공했습니다.", response));
    }

    @Operation(summary = "기관별 장비 목록 조회",
            description = """
                    검사기관의 장비 목록을 이름순으로 조회합니다. 장비 종류로 필터링할 수 있으며 자기 기관 또는 활성 협약 기관에 한해 조회됩니다.
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = """
                    - MISSING_HEADER: X-User-Id·X-Institution-Id·X-Role 헤더 누락 (직접 호출 시)
                    - INVALID_INPUT: UUID·역할·modality 형식 오류, page < 0, size가 1~100 범위 밖
                    """, content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "FORBIDDEN: 허용되지 않은 역할, 비활성 기관, 검사기관이 아님, 자기 기관·활성 협약 기관이 아님",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "RESOURCE_NOT_FOUND: 요청자 기관 또는 조회 대상 기관이 없거나 삭제됨",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/institutions/{institutionId}/equipments")
    public ResponseEntity<ApiResponse<PagedResponse<EquipmentDetailResponse>>> getEquipments(
            @Parameter(description = "조회할 검사기관 ID") @PathVariable("institutionId") UUID institutionId,
            @Parameter(description = "요청자 ID") @RequestHeader("X-User-Id") UUID userId,
            @Parameter(description = "요청자 소속 기관 ID") @RequestHeader("X-Institution-Id") UUID userInstitutionId,
            @Parameter(description = "요청자 권한") @RequestHeader("X-Role") Role userRole,
            @Parameter(description = "장비 종류 필터 (생략 시 전체)") @RequestParam(name = "modality", required = false) Modality modality,
            @Parameter(description = "페이지 번호 (0부터)") @RequestParam(name = "page", defaultValue = "0") Integer page,
            @Parameter(description = "페이지 크기 (1~100)") @RequestParam(name = "size", defaultValue = "20") Integer size
    ) {
        PagedResponse<EquipmentDetailResponse> response = equipmentService.getEquipments(
                institutionId,
                userId,
                userInstitutionId,
                userRole,
                modality,
                page,
                size
        );

        return ResponseEntity.ok(ApiResponse.ok("장비 목록 조회에 성공했습니다.", response));
    }

    @Operation(summary = "[EXAMINER] 장비 정보 수정",
            description = """
                    자기 기관 장비의 이름을 수정합니다. 장비 종류·운영 상태는 변경할 수 없습니다.
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "수정 완료"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = """
                    - VALIDATION_FAILED: 장비명(최대 50자) 누락 또는 길이 초과
                    - MISSING_HEADER: X-User-Id·X-Institution-Id·X-Role 헤더 누락 (직접 호출 시)
                    - INVALID_INPUT: UUID·역할 값 형식 오류 또는 요청 본문 형식 오류
                    """, content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "FORBIDDEN: EXAMINER가 아님, 자기 기관 장비가 아님, 비활성 기관 또는 검사기관이 아님",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "RESOURCE_NOT_FOUND: 장비 또는 기관이 없거나 삭제됨",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "DUPLICATE_EQUIPMENT_NAME: 같은 기관에 같은 이름의 장비가 있음",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PatchMapping("/equipments/{equipmentId}")
    public ResponseEntity<ApiResponse<EquipmentDetailResponse>> updateEquipment(
            @Parameter(description = "장비 ID") @PathVariable("equipmentId") UUID equipmentId,
            @Parameter(description = "요청자 ID") @RequestHeader("X-User-Id") UUID userId,
            @Parameter(description = "요청자 소속 기관 ID") @RequestHeader("X-Institution-Id") UUID userInstitutionId,
            @Parameter(description = "요청자 권한") @RequestHeader("X-Role") Role userRole,
            @Valid @RequestBody EquipmentUpdateRequest request
    ) {
        EquipmentDetailResponse response = equipmentService.updateEquipment(
                equipmentId,
                request,
                userId,
                userInstitutionId,
                userRole
        );

        return ResponseEntity.ok(ApiResponse.ok("장비 정보가 수정되었습니다.", response));
    }
}