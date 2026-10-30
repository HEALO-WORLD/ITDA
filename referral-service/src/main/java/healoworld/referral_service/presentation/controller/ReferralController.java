package healoworld.referral_service.presentation.controller;

import healoworld.referral_service.application.service.ReferralHistoryService;
import healoworld.referral_service.application.service.ReferralService;
import healoworld.referral_service.domain.enums.RoleType;
import healoworld.referral_service.global.dto.ApiResponse;
import healoworld.referral_service.global.dto.PagedResponse;
import healoworld.referral_service.presentation.dto.request.CreateReferralRequest;
import healoworld.referral_service.presentation.dto.request.CreateReservationRequest;
import healoworld.referral_service.presentation.dto.request.ReferralSearchCondition;
import healoworld.referral_service.presentation.dto.response.ReferralHistoryResponse;
import healoworld.referral_service.presentation.dto.response.ReferralResponse;
import healoworld.referral_service.presentation.dto.response.ReservationResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/referrals")
@RequiredArgsConstructor
public class ReferralController {
    private final ReferralService referralService;
    private final ReferralHistoryService referralHistoryService;

    @PostMapping
    public ResponseEntity<ApiResponse<ReferralResponse>> createReferralWithReservation(
            @RequestHeader("X-User-Id") UUID userId,
            @RequestBody @Valid CreateReferralRequest createReferralRequest
    ) {
        ReferralResponse response = referralService.createReferral(userId, createReferralRequest);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok("의뢰 생성, 예약 접수가 완료되었습니다.", response));
    }

    @PostMapping("/{referralId}/reschedule")
    public ResponseEntity<ApiResponse<ReservationResponse>> createRescheduledReservation(
            @RequestHeader("X-User-Id") UUID userId,
            @RequestHeader("X-User-Role") RoleType role,
            @PathVariable("referralId") UUID referralId,
            @RequestBody @Valid CreateReservationRequest request
    ) {
        ReservationResponse response = referralService.createRescheduledReservation(userId, role, referralId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok("재 예약 접수가 완료되었습니다.", response));
    }


    @GetMapping("/{referralId}")
    public ResponseEntity<ApiResponse<ReferralResponse>> getReferral(
            @RequestHeader("X-Institution-Id") UUID institutionId,
            @RequestHeader("X-User-Role") RoleType role,
            @PathVariable("referralId") UUID referralId
    ) {
        ReferralResponse response = referralService.getReferral(institutionId, referralId, role);
        return ResponseEntity.ok(ApiResponse.ok("의뢰 조회가 완료되었습니다.", response));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<PagedResponse<ReferralResponse>>> getReferrals(
            @RequestHeader("X-Institution-Id") UUID institutionId,
            @RequestHeader("X-User-Role") RoleType role,
            @ModelAttribute ReferralSearchCondition cond
    ) {
        PagedResponse<ReferralResponse> response = referralService.getReferrals(institutionId, cond, role);
        return ResponseEntity.ok(ApiResponse.ok("의뢰 목록 조회가 완료되었습니다.", response));
    }

    @GetMapping("/{referralId}/histories")
    public ResponseEntity<ApiResponse<List<ReferralHistoryResponse>>> getReferralHistories(
            @RequestHeader("X-Institution-Id") UUID institutionId,
            @RequestHeader("X-User-Role") RoleType role,
            @PathVariable("referralId") UUID referralId
    ) {
        List<ReferralHistoryResponse> response = referralHistoryService.getReferralHistories(institutionId, referralId, role);
        return ResponseEntity.ok(ApiResponse.ok("의뢰 변경 목록 조회가 완료되었습니다.", response));
    }

    @DeleteMapping("/{referralId}")
    public ResponseEntity<ApiResponse<Void>> deleteReferral(
            @RequestHeader("X-User-Id") UUID userId,
            @RequestHeader("X-User-Role") RoleType role,
            @PathVariable("referralId") UUID referralId
    ) {
        referralService.deleteReferral(userId, referralId, role);
        return ResponseEntity.ok(ApiResponse.ok("삭제가 완료되었습니다.", null));
    }
}
