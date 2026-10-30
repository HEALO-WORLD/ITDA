package healoworld.referral_service.presentation.controller;

import healoworld.referral_service.application.service.ExaminationResultService;
import healoworld.referral_service.global.dto.ApiResponse;
import healoworld.referral_service.presentation.dto.request.ExaminationResultCreateRequest;
import healoworld.referral_service.presentation.dto.response.ExaminationResultCreateResponse;
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
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/examinations")
public class ExaminationController {

    private final ExaminationResultService examinationResultService;

    @PostMapping("/{examinationId}/results")
    public ResponseEntity<ApiResponse<ExaminationResultCreateResponse>> registerResult(
            @RequestHeader("X-Institution-Id") UUID institutionId,
            @PathVariable("examinationId") UUID examinationId,
            @RequestBody @Valid ExaminationResultCreateRequest request
    ) {
        ExaminationResultCreateResponse response = examinationResultService.register(
                examinationId, institutionId, request.summary(), request.notes());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok("검사 결과 등록이 완료되었습니다.", response));
    }
}
