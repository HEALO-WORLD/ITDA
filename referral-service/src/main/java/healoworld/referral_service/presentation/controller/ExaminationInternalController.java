package healoworld.referral_service.presentation.controller;

import healoworld.referral_service.application.service.ExaminationService;
import healoworld.referral_service.global.dto.ApiResponse;
import healoworld.referral_service.presentation.dto.response.ExaminationResponse;
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
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/internal/examinations")
public class ExaminationInternalController {

    private final ExaminationService examinationService;

    @GetMapping("/by-referral/{referralId}")
    public ResponseEntity<ApiResponse<ExaminationResponse>> getByReferralId(@PathVariable UUID referralId) {
        return ResponseEntity.ok(ApiResponse.ok("의뢰별 검사 조회 성공", examinationService.getByReferralId(referralId)));
    }
}
