package healoworld.referral_service.presentation.controller;

import healoworld.referral_service.application.service.ExaminationResultService;
import healoworld.referral_service.global.dto.ApiResponse;
import healoworld.referral_service.presentation.dto.request.ExaminationResultCreateRequest;
import healoworld.referral_service.presentation.dto.response.ExaminationResultCreateResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

// 검사기관 사용자가 호출하는 검사 API
// X-Institution-Id는 Gateway(JwtAuthenticationFilter)가 외부 요청의 같은 헤더를 지운 뒤 JWT 값으로 채워 전달한다는 계약을 전제로 한다
// TODO: Gateway를 거친 실제 요청으로 헤더 전달을 확인하는 E2E 검증은 아직 하지 않았다
@Tag(name = "Examination", description = "검사 결과 API")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/examinations")
public class ExaminationController {

    private final ExaminationResultService examinationResultService;

    @Operation(summary = "검사 결과 등록",
            description = """
                    완료(COMPLETED)된 검사에 결과를 등록합니다. 검사 하나에 결과는 하나만 등록할 수 있고, 등록해도 검사 상태는 바뀌지 않습니다.
                    요청자의 소속 기관이 해당 검사를 수행하는 검사기관과 같아야 합니다. 역할(X-Role)은 확인하지 않습니다.

                    - 400 VALIDATION_FAILED: summary가 없거나 공백
                    - 400 MISSING_HEADER: X-Institution-Id 누락
                    - 400 INVALID_INPUT: 헤더 또는 검사 ID의 형식이 잘못되었거나 요청 본문을 읽을 수 없음
                    - 403 EXAMINATION_RESULT_ACCESS_DENIED: 검사를 수행하는 검사기관 소속이 아님
                    - 404 EXAMINATION_NOT_FOUND: 검사가 없음
                    - 409 EXAMINATION_NOT_COMPLETED: 검사가 완료되지 않음
                    - 409 EXAMINATION_RESULT_ALREADY_EXISTS: 이미 결과가 등록됨
                    """)
    @PostMapping("/{examinationId}/results")
    public ResponseEntity<ApiResponse<ExaminationResultCreateResponse>> registerResult(
            @Parameter(description = "요청자 소속 기관 ID") @RequestHeader("X-Institution-Id") UUID institutionId,
            @Parameter(description = "검사 ID") @PathVariable("examinationId") UUID examinationId,
            @RequestBody @Valid ExaminationResultCreateRequest request
    ) {
        ExaminationResultCreateResponse response = examinationResultService.register(
                examinationId, institutionId, request.summary(), request.notes());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok("검사 결과 등록이 완료되었습니다.", response));
    }
}
