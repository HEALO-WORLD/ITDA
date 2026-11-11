package healoworld.institution_service.presentation.controller;

import healoworld.institution_service.application.service.partnership.PartnershipService;
import healoworld.institution_service.global.dto.ErrorResponse;
import healoworld.institution_service.presentation.dto.response.PartnerExamInstitutionsResponse;
import healoworld.institution_service.presentation.dto.response.PartnershipCheckResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@Tag(name = "Partnership (Internal)", description = """
        협약 상태 확인용 내부 API. Gateway 경유 호출은 불가하며 응답을 ApiResponse로 감싸지 않습니다.
        """)
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/internal/partnerships")
public class PartnershipInternalController {
    private final PartnershipService partnershipService;

    @Operation(summary = "[내부 전용] 활성 협약 여부 확인",
            description = """
                    의뢰기관과 검사기관 간 활성 협약 여부를 반환합니다. 협약이 없으면 active=false입니다.
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "확인 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = """
                    - INVALID_INPUT: 기관 ID가 UUID 형식이 아님
                    - UNSUPPORTED_REQUEST: 필수 쿼리 파라미터 누락
                    """, content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/check")
    public PartnershipCheckResponse check(@Parameter(description = "의뢰기관 ID") @RequestParam UUID requesterInstitutionId,
                                          @Parameter(description = "검사기관 ID") @RequestParam UUID examInstitutionId) {
        return new PartnershipCheckResponse(
                partnershipService.isActivePartnership(requesterInstitutionId, examInstitutionId));
    }

    @Operation(summary = "[내부 전용] 활성 협약 검사기관 ID 목록 조회",
            description = """
                    의뢰기관과 활성 협약 중인 검사기관 ID 목록을 반환합니다. 협약이 없으면 빈 목록입니다.
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = """
                    - INVALID_INPUT: 기관 ID가 UUID 형식이 아님
                    - UNSUPPORTED_REQUEST: 필수 쿼리 파라미터 누락
                    """, content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/exam-institutions")
    public PartnerExamInstitutionsResponse examInstitutions(
            @Parameter(description = "의뢰기관 ID") @RequestParam UUID requesterInstitutionId) {
        return new PartnerExamInstitutionsResponse(
                partnershipService.getPartnerExamInstitutionIds(requesterInstitutionId));
    }
}
