package healoworld.referral_service.presentation.controller;

import healoworld.referral_service.application.service.ExaminationService;
import healoworld.referral_service.application.service.ExaminationResultService;
import healoworld.referral_service.domain.enums.RoleType;
import healoworld.referral_service.global.dto.ApiResponse;
import healoworld.referral_service.global.dto.PagedResponse;
import healoworld.referral_service.presentation.dto.request.ExaminationResultCreateRequest;
import healoworld.referral_service.presentation.dto.request.ExaminationStatusChangeRequest;
import healoworld.referral_service.presentation.dto.response.ExaminationListResponse;
import healoworld.referral_service.presentation.dto.response.ExaminationResultCreateResponse;
import healoworld.referral_service.presentation.dto.response.ExaminationResultResponse;
import healoworld.referral_service.presentation.dto.response.ExaminationStatusChangeResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

// 검사기관 사용자가 호출하는 검사 API
// X-Institution-Id는 Gateway가 JWT에서 추출한 값을 전달한다는 계약을 전제로 한다.
// TODO: Gateway를 통한 실제 헤더 전달은 E2E 테스트에서 검증한다.
@Tag(name = "Examination", description = "검사 상태 관리 및 검사 결과 API")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/examinations")
public class ExaminationController {

    private final ExaminationService examinationService;
    private final ExaminationResultService examinationResultService;

    // ==============================
    // 검사기관용 검사 목록 조회
    // ==============================

    @Operation(
            summary = "검사 목록 조회 (검사기관)",
            description = """
                    요청자가 소속된 검사기관의 검사 목록을 조회합니다.
                    
                    - EXAMINER 역할만 조회할 수 있습니다.
                    - 요청자의 소속 검사기관에 해당하는 검사만 반환합니다.
                    - 페이지 번호는 0부터 시작합니다.
                    - 페이지 크기는 1~100건입니다.
                    - 생성일 내림차순으로 조회하며,
                      생성일이 같으면 검사 ID 내림차순으로 정렬합니다.
                    
                    오류 응답:
                    - 400 MISSING_HEADER: 필수 헤더 누락
                    - 400 INVALID_INPUT: 잘못된 UUID 또는 역할값
                    - 400: 유효하지 않은 페이지 번호 또는 크기
                    - 403 EXAMINATION_ACCESS_DENIED: EXAMINER 역할이 아님
                    """
    )
    @GetMapping
    public ResponseEntity<ApiResponse<PagedResponse<ExaminationListResponse>>> getExaminations(

            @Parameter(description = "요청자 소속 기관 ID")
            @RequestHeader("X-Institution-Id") UUID institutionId,

            @Parameter(description = "요청자 역할")
            @RequestHeader("X-Role") RoleType role,

            @Parameter(description = "페이지 번호 (0부터 시작)")
            @RequestParam(defaultValue = "0")
            @Min(0) int page,

            @Parameter(description = "페이지 크기 (1~100)")
            @RequestParam(defaultValue = "10")
            @Min(1) @Max(100) int size
    ) {

        // 기본 정렬: 생성일 최신순, 동일 생성일이면 ID 내림차순
        Sort sort = Sort.by(
                Sort.Order.desc("createdAt"),
                Sort.Order.desc("id")
        );

        PageRequest pageable = PageRequest.of(page, size, sort);

        PagedResponse<ExaminationListResponse> response =
                examinationService.getExaminations(
                        institutionId,
                        role,
                        pageable
                );

        return ResponseEntity.ok(
                ApiResponse.ok(
                        "검사 목록 조회가 완료되었습니다.",
                        response
                )
        );
    }

    // ==============================
    // 검사 결과 등록
    // ==============================

    @Operation(
            summary = "검사 결과 등록",
            description = """
                    완료(COMPLETED)된 검사에 결과를 등록합니다.
                    
                    검사 하나에 결과는 하나만 등록할 수 있으며,
                    결과 등록 후에도 검사 상태는 변경되지 않습니다.
                    
                    요청자의 소속 기관이 검사를 수행한 검사기관과 일치해야 합니다.
                    현재 등록 API는 X-Role을 별도로 확인하지 않습니다.
                    
                    오류 응답:
                    - 400 VALIDATION_FAILED: summary 누락 또는 공백
                    - 400 MISSING_HEADER: 기관 ID 헤더 누락
                    - 400 INVALID_INPUT: 잘못된 UUID 또는 요청 본문
                    - 403 EXAMINATION_RESULT_ACCESS_DENIED: 검사기관 불일치
                    - 404 EXAMINATION_NOT_FOUND: 검사 없음
                    - 409 EXAMINATION_NOT_COMPLETED: 검사가 완료되지 않음
                    - 409 EXAMINATION_RESULT_ALREADY_EXISTS: 결과 중복 등록
                    """
    )
    @PostMapping("/{examinationId}/results")
    public ResponseEntity<ApiResponse<ExaminationResultCreateResponse>> registerResult(
            @Parameter(description = "요청자 소속 기관 ID")
            @RequestHeader("X-Institution-Id") UUID institutionId,

            @Parameter(description = "검사 ID")
            @PathVariable("examinationId") UUID examinationId,

            @RequestBody @Valid ExaminationResultCreateRequest request
    ) {
        ExaminationResultCreateResponse response =
                examinationResultService.register(
                        examinationId,
                        institutionId,
                        request.summary(),
                        request.notes()
                );

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(
                        "검사 결과 등록이 완료되었습니다.",
                        response
                ));
    }

    // ==============================
    // 검사기관용 검사 결과 조회
    // ==============================

    @Operation(
            summary = "검사 결과 조회 (검사기관)",
            description = """
                    검사 ID로 등록된 검사 결과를 조회합니다.
                    
                    - EXAMINER 역할만 조회할 수 있습니다.
                    - 요청자의 소속 기관이 해당 검사를 수행한 검사기관과
                      일치해야 합니다.
                    - 권한 검증은 Service 계층에서 수행합니다.
                    
                    오류 응답:
                    - 400 MISSING_HEADER: 필수 헤더 누락
                    - 400 INVALID_INPUT: 잘못된 UUID 또는 역할값
                    - 403 EXAMINATION_RESULT_READ_ACCESS_DENIED: 역할 또는 소속 기관 불일치
                    - 404 EXAMINATION_NOT_FOUND: 검사 없음
                    - 404 EXAMINATION_RESULT_NOT_FOUND: 검사 결과 없음
                    """
    )
    @GetMapping("/{examinationId}/results")
    public ResponseEntity<ApiResponse<ExaminationResultResponse>> getResultByExaminationId(
            @Parameter(description = "요청자 소속 기관 ID")
            @RequestHeader("X-Institution-Id") UUID institutionId,

            @Parameter(description = "요청자 역할")
            @RequestHeader("X-Role") RoleType role,

            @Parameter(description = "검사 ID")
            @PathVariable("examinationId") UUID examinationId
    ) {
        ExaminationResultResponse response =
                examinationResultService.getResultByExaminationId(
                        examinationId,
                        institutionId,
                        role
                );

        return ResponseEntity.ok(
                ApiResponse.ok(
                        "검사 결과 조회가 완료되었습니다.",
                        response
                )
        );
    }

    // ==============================
    // 검사 상태 변경
    // ==============================

    @Operation(
            summary = "검사 상태 변경",
            description = """
                    검사 시작 또는 완료 상태로 변경합니다.
                    
                    - EXAMINER 역할만 요청할 수 있습니다.
                    - 요청자의 소속 기관이 검사를 수행하는 검사기관과 일치해야 합니다.
                    - SCHEDULED → IN_PROGRESS → COMPLETED 순서로 변경할 수 있습니다.
                    - 같은 상태로 다시 요청하면 기존 상태를 반환합니다.
                    
                    오류 응답:
                    - 400 VALIDATION_FAILED: status 누락
                    - 400 INVALID_INPUT: 잘못된 UUID 또는 상태값
                    - 400 MISSING_HEADER: 필수 헤더 누락
                    - 403 EXAMINATION_ACCESS_DENIED: 역할 또는 기관 불일치
                    - 404 EXAMINATION_NOT_FOUND: 검사 없음
                    - 409 INVALID_EXAMINATION_STATUS_TRANSITION: 허용되지 않은 상태 전환
                    - 409 EXAMINATION_START_NOT_ALLOWED: 유효하지 않은 예약
                    """
    )
    @PatchMapping("/{examinationId}/status")
    public ResponseEntity<ApiResponse<ExaminationStatusChangeResponse>> changeStatus(
            @Parameter(description = "요청자 소속 기관 ID")
            @RequestHeader("X-Institution-Id") UUID institutionId,

            @Parameter(description = "요청자 역할")
            @RequestHeader("X-Role") RoleType role,

            @Parameter(description = "검사 ID")
            @PathVariable("examinationId") UUID examinationId,

            @RequestBody @Valid ExaminationStatusChangeRequest request
    ) {
        ExaminationStatusChangeResponse response =
                examinationService.changeStatus(
                        examinationId,
                        institutionId,
                        role,
                        request.status()
                );

        return ResponseEntity.ok(
                ApiResponse.ok(
                        "검사 상태 변경이 완료되었습니다.",
                        response
                )
        );
    }
}