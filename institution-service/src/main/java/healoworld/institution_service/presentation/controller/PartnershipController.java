package healoworld.institution_service.presentation.controller;

import healoworld.institution_service.application.service.partnership.PartnershipService;
import healoworld.institution_service.domain.enums.PartnershipStatus;
import healoworld.institution_service.domain.enums.Role;
import healoworld.institution_service.global.dto.ApiResponse;
import healoworld.institution_service.global.dto.ErrorResponse;
import healoworld.institution_service.presentation.dto.request.PartnershipCreateRequest;
import healoworld.institution_service.presentation.dto.response.MyPartnershipResponse;
import healoworld.institution_service.presentation.dto.response.PartnershipResponse;
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

@Tag(name = "Partnership", description = """
        의뢰기관·검사기관 간 협약 신청·승인·종료 API (인증 필요).
        """)
@SecurityRequirement(name = "gatewayBearerAuth")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/partnerships")
public class PartnershipController {
    private final PartnershipService partnershipService;

    @Operation(summary = "내 기관 협약 목록 조회",
            description = """
                    내 기관이 참여한 협약을 조회합니다. 협약 상태로 필터링할 수 있습니다.
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = """
                    - MISSING_HEADER: X-Institution-Id 헤더 누락 (직접 호출 시)
                    - INVALID_INPUT: X-Institution-Id 또는 status 값 형식 오류
                    """, content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/me")
    public ResponseEntity<ApiResponse<List<MyPartnershipResponse>>> getMyPartnerShips(
            @Parameter(description = "요청자 소속 기관 ID") @RequestHeader("X-Institution-Id") UUID institutionId,
            @Parameter(description = "협약 상태 필터 (생략 시 전체)") @RequestParam(required = false) PartnershipStatus status
    ) {
        return ResponseEntity.ok(ApiResponse.ok("내 기관 협약 목록입니다.",
                partnershipService.getMyPartnerships(institutionId, status)));

    }

    @Operation(summary = "[REQUESTER] 협약 요청",
            description = """
                    의뢰기관이 검사기관에 협약을 요청합니다. 활성·대기 협약은 중복 요청할 수 없으며 종료·거절 건은 재요청할 수 있습니다.
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "요청 완료 (신규 또는 재요청, 상태 PENDING)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = """
                    - VALIDATION_FAILED: examInstitutionId 누락
                    - MISSING_HEADER: X-Institution-Id 헤더 누락 (직접 호출 시)
                    - INVALID_INPUT: UUID 형식 또는 요청 본문 형식 오류
                    - INVALID_PARTNERSHIP_INSTITUTION: 요청자가 의뢰기관이 아니거나 상대가 검사기관이 아님
                    """, content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "INSTITUTION_NOT_FOUND: 요청자 기관 또는 상대 기관이 없거나 삭제됨",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "PARTNERSHIP_ALREADY_IN_PROGRESS: 이미 PENDING·ACTIVE 협약이 있음",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping
    public ResponseEntity<ApiResponse<PartnershipResponse>> request(
            @Parameter(description = "요청자 소속 기관 ID") @RequestHeader("X-Institution-Id") UUID myInstitutionId,
            @Valid @RequestBody PartnershipCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("협약을 요청했습니다.",
                        partnershipService.request(myInstitutionId, request.examInstitutionId())));
    }

    @Operation(summary = "[EXAMINER] 협약 승인",
            description = """
                    검사기관이 대기 중인 협약을 승인합니다(PENDING → ACTIVE).
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "승인 완료"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = """
                    - MISSING_HEADER: X-Institution-Id 헤더 누락 (직접 호출 시)
                    - INVALID_INPUT: UUID 형식 오류
                    """, content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "FORBIDDEN: 협약의 검사기관이 아님",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "PARTNERSHIP_NOT_FOUND: 협약이 없거나 삭제됨",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "INVALID_PARTNERSHIP_STATUS: PENDING 상태가 아님",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PatchMapping("/{partnershipId}/approve")
    public ResponseEntity<ApiResponse<PartnershipResponse>> approve(
            @Parameter(description = "협약 ID") @PathVariable UUID partnershipId,
            @Parameter(description = "요청자 소속 기관 ID") @RequestHeader("X-Institution-Id") UUID myInstitutionId) {
        return ResponseEntity.ok(ApiResponse.ok("협약을 승인했습니다.",
                partnershipService.approve(partnershipId, myInstitutionId)));
    }

    @Operation(summary = "[EXAMINER] 협약 거절",
            description = """
                    검사기관이 대기 중인 협약을 거절합니다(PENDING → REJECTED).
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "거절 완료"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = """
                    - MISSING_HEADER: X-Institution-Id 헤더 누락 (직접 호출 시)
                    - INVALID_INPUT: UUID 형식 오류
                    """, content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "FORBIDDEN: 협약의 검사기관이 아님",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "PARTNERSHIP_NOT_FOUND: 협약이 없거나 삭제됨",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "INVALID_PARTNERSHIP_STATUS: PENDING 상태가 아님",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PatchMapping("/{partnershipId}/reject")
    public ResponseEntity<ApiResponse<PartnershipResponse>> reject(
            @Parameter(description = "협약 ID") @PathVariable UUID partnershipId,
            @Parameter(description = "요청자 소속 기관 ID") @RequestHeader("X-Institution-Id") UUID myInstitutionId) {
        return ResponseEntity.ok(ApiResponse.ok("협약을 거절했습니다.",
                partnershipService.reject(partnershipId, myInstitutionId)));
    }

    @Operation(summary = "협약 종료",
            description = """
                    활성 협약을 종료합니다(ACTIVE → TERMINATED). 기존 의뢰·예약 상태는 변경하지 않습니다.
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "종료 완료"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = """
                    - MISSING_HEADER: X-Institution-Id·X-Role 헤더 누락 (직접 호출 시)
                    - INVALID_INPUT: UUID·역할 값 형식 오류
                    """, content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "FORBIDDEN: ADMIN이 아니고 협약 당사자 기관도 아님",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "PARTNERSHIP_NOT_FOUND: 협약이 없거나 삭제됨",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "INVALID_PARTNERSHIP_STATUS: ACTIVE 상태가 아님",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PatchMapping("/{partnershipId}/terminate")
    public ResponseEntity<ApiResponse<PartnershipResponse>> terminate(
            @Parameter(description = "협약 ID") @PathVariable UUID partnershipId,
            @Parameter(description = "요청자 소속 기관 ID") @RequestHeader("X-Institution-Id") UUID myInstitutionId,
            @Parameter(description = "요청자 권한") @RequestHeader("X-Role") Role role) {
        return ResponseEntity.ok(ApiResponse.ok("협약을 종료했습니다.",
                partnershipService.terminate(partnershipId, myInstitutionId, role)));
    }
}
