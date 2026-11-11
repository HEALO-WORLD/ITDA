package healoworld.institution_service.presentation.controller;

import healoworld.institution_service.application.service.institution.InstitutionService;
import healoworld.institution_service.domain.enums.Role;
import healoworld.institution_service.global.dto.ApiResponse;
import healoworld.institution_service.global.dto.ErrorResponse;
import healoworld.institution_service.presentation.dto.request.InstitutionCreateRequest;
import healoworld.institution_service.presentation.dto.request.InstitutionUpdateRequest;
import healoworld.institution_service.presentation.dto.response.InstitutionResponse;
import healoworld.institution_service.presentation.dto.response.PublicInstitutionResponse;
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

import java.util.List;
import java.util.UUID;

@Tag(name = "Institution", description = """
        의료기관 조회·관리 API. 가입 가능 기관 목록 조회만 공개됩니다.
        """)
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/institutions")
public class InstitutionController {
    private final InstitutionService institutionService;

    @Operation(summary = "가입 가능한 기관 목록 조회 (공개)",
            description = """
                    회원가입 시 선택할 수 있는 활성 의뢰기관·검사기관을 조회합니다. 인증은 필요하지 않습니다.
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공")
    })
    @GetMapping("/public")
    public ResponseEntity<ApiResponse<List<PublicInstitutionResponse>>> getPublicInstitutions() {
        return ResponseEntity.ok(ApiResponse.ok("가입 가능한 기관 목록입니다.",
                institutionService.getPublicInstitutions()));
    }

    @Operation(summary = "[ADMIN] 기관 등록",
            description = """
                    의뢰기관 또는 검사기관을 등록합니다. 요양기관기호는 중복될 수 없습니다.
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "등록 완료"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = """
                    - VALIDATION_FAILED: 이름(최대 100자)·유형·주소(최대 255자)·요양기관기호(숫자 8자리) 검증 실패
                    - INVALID_INPUT: 요청 본문 형식 오류 또는 없는 기관 유형 값
                    - INSTITUTION_TYPE_NOT_ALLOWED: 운영사(PLATFORM) 기관 등록 요청
                    """, content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "DUPLICATE_CARE_INSTITUTION_CODE: 이미 등록된 요양기관기호",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @SecurityRequirement(name = "gatewayBearerAuth")
    @PostMapping
    public ResponseEntity<ApiResponse<InstitutionResponse>> create(
            @Valid @RequestBody InstitutionCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("기관이 등록되었습니다.", institutionService.create(request)));
    }

    @Operation(summary = "[ADMIN] 기관 목록 조회",
            description = """
                    삭제되지 않은 모든 기관을 최신 등록순으로 조회합니다. 비활성 기관과 운영사를 포함합니다.
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공")
    })
    @SecurityRequirement(name = "gatewayBearerAuth")
    @GetMapping
    public ResponseEntity<ApiResponse<List<InstitutionResponse>>> getInstitutions() {
        return ResponseEntity.ok(ApiResponse.ok("기관 목록입니다.", institutionService.getInstitutions()));
    }

    @Operation(summary = "[ADMIN] 기관 정보 수정",
            description = """
                    기관 이름과 주소를 수정합니다. 요양기관기호와 기관 유형은 변경할 수 없습니다.
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "수정 완료"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = """
                    - VALIDATION_FAILED: 이름(최대 100자)·주소(최대 255자) 검증 실패
                    - INVALID_INPUT: institutionId 형식 또는 요청 본문 형식 오류
                    """, content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "INSTITUTION_NOT_FOUND: 기관이 없거나 삭제됨",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @SecurityRequirement(name = "gatewayBearerAuth")
    @PatchMapping("/{institutionId}")
    public ResponseEntity<ApiResponse<InstitutionResponse>> update(
            @Parameter(description = "기관 ID") @PathVariable UUID institutionId,
            @Valid @RequestBody InstitutionUpdateRequest request) {
        return ResponseEntity.ok(ApiResponse.ok("기관 정보가 수정되었습니다.",
                institutionService.update(institutionId, request)));
    }

    @Operation(summary = "[ADMIN] 기관 삭제",
            description = """
                    기관을 비활성화하고 소프트 삭제합니다. 진행 중인 협약이나 소속 사용자가 있으면 삭제할 수 없습니다.
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "삭제 완료 (data는 null)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = """
                    - MISSING_HEADER: X-User-Id 헤더 누락 (직접 호출 시)
                    - INVALID_INPUT: institutionId·X-User-Id가 UUID 형식이 아님
                    """, content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "INSTITUTION_NOT_FOUND: 기관이 없거나 이미 삭제됨",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = """
                    - INSTITUTION_HAS_ACTIVE_PARTNERSHIP: PENDING·ACTIVE 협약이 있음
                    - INSTITUTION_HAS_USERS: 소속 사용자가 있음
                    """, content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @SecurityRequirement(name = "gatewayBearerAuth")
    @DeleteMapping("/{institutionId}")
    public ResponseEntity<ApiResponse<Void>> delete(
            @Parameter(description = "삭제할 기관 ID") @PathVariable UUID institutionId,
            @Parameter(description = "요청 관리자 ID") @RequestHeader("X-User-Id") UUID adminId) {
        institutionService.delete(institutionId, adminId);
        return ResponseEntity.ok(ApiResponse.ok("기관이 삭제되었습니다.", null));
    }

    @Operation(summary = "기관 상세 조회",
            description = """
                    기관 정보를 조회합니다. 관리자는 전체 기관을, 일반 사용자는 자기 기관과 협약 상대 기관만 조회할 수 있습니다.
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = """
                    - MISSING_HEADER: X-Institution-Id·X-Role 헤더 누락 (직접 호출 시)
                    - INVALID_INPUT: UUID 형식 또는 역할 값이 잘못됨
                    """, content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "FORBIDDEN: 자기 기관도, 협약(PENDING·ACTIVE) 상대 기관도 아님",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "INSTITUTION_NOT_FOUND: 기관이 없거나 삭제됨",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @SecurityRequirement(name = "gatewayBearerAuth")
    @GetMapping("/{institutionId}")
    public ResponseEntity<ApiResponse<InstitutionResponse>> getInstitutionDetail(
            @Parameter(description = "조회할 기관 ID") @PathVariable UUID institutionId,
            @Parameter(description = "요청자 소속 기관 ID") @RequestHeader("X-Institution-Id") UUID myInstitutionId,
            @Parameter(description = "요청자 권한") @RequestHeader("X-Role") Role role) {
        return ResponseEntity.ok(ApiResponse.ok("기관 상세 정보입니다.",
                institutionService.getInstitutionDetail(institutionId, myInstitutionId, role)));
    }
}
