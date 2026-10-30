package healoworld.referral_service.presentation.controller;

import healoworld.referral_service.application.service.ExaminationService;
import healoworld.referral_service.global.dto.ApiResponse;
import healoworld.referral_service.presentation.dto.response.ExaminationResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

// 다른 서비스가 호출하는 내부 API
// Gateway는 /api/v1/internal/** 경로를 라우팅하지 않으므로 외부 사용자는 Gateway를 통해 접근할 수 없다
// 서비스 간 인증 방식은 아직 정해지지 않았다 (팀 협의 필요)
@Tag(name = "Examination (Internal)", description = "내부 서비스 전용 검사 API. Gateway를 통해 호출할 수 없습니다.")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/internal/examinations")
public class ExaminationInternalController {

    private final ExaminationService examinationService;

    @Operation(summary = "[내부 전용] 의뢰별 검사 조회",
            description = """
                    내부 서비스 전용 API입니다. 의뢰 ID로 검사 상태를 조회합니다.
                    Gateway는 /api/v1/internal/** 요청을 404로 차단합니다. 서비스 간 인증 방식은 아직 정해지지 않았습니다.

                    - 400 INVALID_INPUT: 의뢰 ID의 형식이 잘못됨
                    - 404 EXAMINATION_NOT_FOUND: 해당 의뢰의 검사가 없음
                    """)
    @GetMapping("/by-referral/{referralId}")
    public ResponseEntity<ApiResponse<ExaminationResponse>> getByReferralId(
            @Parameter(description = "의뢰 ID") @PathVariable UUID referralId) {
        return ResponseEntity.ok(ApiResponse.ok("의뢰별 검사 조회 성공", examinationService.getByReferralId(referralId)));
    }
}
