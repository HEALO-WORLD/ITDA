package healoworld.institution_service.presentation.controller;

import healoworld.institution_service.application.service.examtype.ExamTypeService;
import healoworld.institution_service.domain.enums.Modality;
import healoworld.institution_service.domain.enums.Role;
import healoworld.institution_service.global.dto.ApiResponse;
import healoworld.institution_service.global.dto.ErrorResponse;
import healoworld.institution_service.global.dto.PagedResponse;
import healoworld.institution_service.presentation.dto.request.ExamTypeCreateRequest;
import healoworld.institution_service.presentation.dto.request.ExamTypeUpdateRequest;
import healoworld.institution_service.presentation.dto.response.ExamTypeCreateResponse;
import healoworld.institution_service.presentation.dto.response.ExamTypeResponse;
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

@Tag(name = "Exam Type", description = """
        CT·MRI 검사 유형 관리 API (인증 필요).
        """)
@SecurityRequirement(name = "gatewayBearerAuth")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/exam-types")
public class ExamTypeController {

    private final ExamTypeService examTypeService;

    @Operation(summary = "[ADMIN] 검사 유형 등록",
            description = """
                    CT·MRI 검사 유형을 등록합니다. 같은 검사 방식에서 이름 중복은 허용되지 않습니다.
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "등록 완료"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = """
                    - VALIDATION_FAILED: 검사명(최대 50자)·검사 방식 누락 또는 형식 오류
                    - MISSING_HEADER: X-User-Id·X-Role 헤더 누락 (직접 호출 시)
                    - INVALID_INPUT: 헤더 형식, 요청 본문 형식, 없는 검사 방식 값
                    """, content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "FORBIDDEN: ADMIN이 아님",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "DUPLICATE_EXAM_TYPE: 같은 이름·검사 방식의 검사 유형이 이미 있음",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping
    public ResponseEntity<ApiResponse<ExamTypeCreateResponse>> createExamType(
            @Parameter(description = "요청자 ID") @RequestHeader("X-User-Id") UUID userId,
            @Parameter(description = "요청자 권한") @RequestHeader("X-Role") Role userRole,
            @Valid @RequestBody ExamTypeCreateRequest request
    ) {

        ExamTypeCreateResponse response = examTypeService.createExamType(request, userId, userRole);

        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok("검사 유형이 등록되었습니다.", response));
    }

    @Operation(summary = "검사 유형 목록 조회",
            description = """
                    검사 유형을 이름순으로 조회합니다. 검사 방식으로 필터링할 수 있습니다.
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = """
                    - MISSING_HEADER: X-User-Id·X-Role 헤더 누락 (직접 호출 시)
                    - INVALID_INPUT: 헤더·modality 형식 오류, page < 0, size가 1~100 범위 밖
                    """, content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping
    public ResponseEntity<ApiResponse<PagedResponse<ExamTypeResponse>>> getExamTypes(
            @Parameter(description = "요청자 ID") @RequestHeader("X-User-Id") UUID userId,
            @Parameter(description = "요청자 권한") @RequestHeader("X-Role") Role userRole,
            @Parameter(description = "검사 방식 필터 (생략 시 전체)") @RequestParam(name = "modality", required = false) Modality modality,
            @Parameter(description = "페이지 번호 (0부터)") @RequestParam(name = "page", defaultValue = "0") Integer page,
            @Parameter(description = "페이지 크기 (1~100)") @RequestParam(name = "size", defaultValue = "20") Integer size
    ) {
        PagedResponse<ExamTypeResponse> response = examTypeService.getExamTypes(userId, userRole, modality, page, size);

        return ResponseEntity.ok(ApiResponse.ok("검사 유형 목록 조회에 성공했습니다.", response));
    }

    @Operation(summary = "[ADMIN] 검사 유형 수정",
            description = """
                    검사 유형 이름을 수정합니다. 검사 방식은 변경할 수 없습니다.
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "수정 완료"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = """
                    - VALIDATION_FAILED: 검사명(최대 50자) 누락 또는 길이 초과
                    - MISSING_HEADER: X-User-Id·X-Role 헤더 누락 (직접 호출 시)
                    - INVALID_INPUT: examTypeId·헤더 형식 오류 또는 요청 본문 형식 오류
                    """, content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "FORBIDDEN: ADMIN이 아님",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "RESOURCE_NOT_FOUND: 검사 유형이 없거나 삭제됨",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "DUPLICATE_EXAM_TYPE: 같은 이름·검사 방식의 검사 유형이 이미 있음",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PatchMapping("/{examTypeId}")
    public ResponseEntity<ApiResponse<ExamTypeResponse>> updateExamType(
            @Parameter(description = "검사 유형 ID") @PathVariable("examTypeId") UUID examTypeId,
            @Parameter(description = "요청자 ID") @RequestHeader("X-User-Id") UUID userId,
            @Parameter(description = "요청자 권한") @RequestHeader("X-Role") Role userRole,
            @Valid @RequestBody ExamTypeUpdateRequest request
    ) {
        ExamTypeResponse response = examTypeService.updateExamType(
                examTypeId, request, userId, userRole
        );

        return ResponseEntity.ok(
                ApiResponse.ok("검사 유형이 수정되었습니다.", response)
        );
    }
}