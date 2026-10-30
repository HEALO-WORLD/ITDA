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
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@Tag(name = "Referral", description = "검사 의뢰 API")
@RestController
@RequestMapping("/api/v1/referrals")
@RequiredArgsConstructor
public class ReferralController {
    private final ReferralService referralService;
    private final ReferralHistoryService referralHistoryService;

    @Operation(summary = "의뢰 생성 + 첫 예약 접수",
            description = """
                    의뢰를 만들고 첫 예약(attemptNo=1)을 REQUESTED 상태로 접수합니다.
                    같은 idempotencyKey로 다시 요청하면 새로 만들지 않고 기존 의뢰를 반환합니다.

                    - 400: 필수값 누락, 헤더 누락
                    """)
    @PostMapping
    public ResponseEntity<ApiResponse<ReferralResponse>> createReferralWithReservation(
            @Parameter(description = "요청자 ID") @RequestHeader("X-User-Id") UUID userId,
            @RequestBody @Valid CreateReferralRequest createReferralRequest
    ) {
        ReferralResponse response = referralService.createReferral(userId, createReferralRequest);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok("의뢰 생성, 예약 접수가 완료되었습니다.", response));
    }

    @Operation(summary = "재예약 접수",
            description = """
                    NEEDS_RESCHEDULE 상태의 의뢰에 새 예약을 접수하고 의뢰를 PENDING으로 되돌립니다.
                    작성자 본인 또는 관리자만 가능합니다.

                    - 404 REFERRAL_NOT_FOUND: 의뢰가 없거나 권한 없음
                    - 409 INVALID_REFERRAL_STATUS: NEEDS_RESCHEDULE 상태가 아님
                    """)
    @PostMapping("/{referralId}/reschedule")
    public ResponseEntity<ApiResponse<ReservationResponse>> createRescheduledReservation(
            @Parameter(description = "요청자 ID") @RequestHeader("X-User-Id") UUID userId,
            @Parameter(description = "요청자 권한") @RequestHeader("X-User-Role") RoleType role,
            @Parameter(description = "의뢰 ID") @PathVariable("referralId") UUID referralId,
            @RequestBody @Valid CreateReservationRequest request
    ) {
        ReservationResponse response = referralService.createRescheduledReservation(userId, role, referralId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok("재 예약 접수가 완료되었습니다.", response));
    }


    @Operation(summary = "의뢰 단건 조회",
            description = """
                    의뢰기관 소속 또는 관리자만 조회할 수 있습니다.

                    - 404 REFERRAL_NOT_FOUND: 의뢰가 없거나 권한 없음
                    """)
    @GetMapping("/{referralId}")
    public ResponseEntity<ApiResponse<ReferralResponse>> getReferral(
            @Parameter(description = "요청자 소속 기관 ID") @RequestHeader("X-Institution-Id") UUID institutionId,
            @Parameter(description = "요청자 권한") @RequestHeader("X-User-Role") RoleType role,
            @Parameter(description = "의뢰 ID") @PathVariable("referralId") UUID referralId
    ) {
        ReferralResponse response = referralService.getReferral(institutionId, referralId, role);
        return ResponseEntity.ok(ApiResponse.ok("의뢰 조회가 완료되었습니다.", response));
    }

    @Operation(summary = "의뢰 목록 조회",
            description = """
                    본인 기관의 의뢰만 조회됩니다. 관리자는 전체를 조회합니다.
                    내가 작성한 의뢰만 보려면 referrerId에 본인 ID를 넣습니다.
                    """)
    @GetMapping
    public ResponseEntity<ApiResponse<PagedResponse<ReferralResponse>>> getReferrals(
            @Parameter(description = "요청자 소속 기관 ID") @RequestHeader("X-Institution-Id") UUID institutionId,
            @Parameter(description = "요청자 권한") @RequestHeader("X-User-Role") RoleType role,
            @ParameterObject @ModelAttribute ReferralSearchCondition cond
    ) {
        PagedResponse<ReferralResponse> response = referralService.getReferrals(institutionId, cond, role);
        return ResponseEntity.ok(ApiResponse.ok("의뢰 목록 조회가 완료되었습니다.", response));
    }

    @Operation(summary = "의뢰 변경 내역 조회",
            description = """
                    의뢰 상태 변경 이력을 발생 시각 오름차순으로 반환합니다.
                    의뢰기관 소속 또는 관리자만 조회할 수 있습니다.

                    - 404 REFERRAL_NOT_FOUND: 의뢰가 없거나 권한 없음
                    """)
    @GetMapping("/{referralId}/histories")
    public ResponseEntity<ApiResponse<List<ReferralHistoryResponse>>> getReferralHistories(
            @Parameter(description = "요청자 소속 기관 ID") @RequestHeader("X-Institution-Id") UUID institutionId,
            @Parameter(description = "요청자 권한") @RequestHeader("X-User-Role") RoleType role,
            @Parameter(description = "의뢰 ID") @PathVariable("referralId") UUID referralId
    ) {
        List<ReferralHistoryResponse> response = referralHistoryService.getReferralHistories(institutionId, referralId, role);
        return ResponseEntity.ok(ApiResponse.ok("의뢰 변경 목록 조회가 완료되었습니다.", response));
    }

    @Operation(summary = "의뢰 삭제",
            description = """
                    FAILED, CANCELED 상태의 의뢰만 soft delete 합니다.
                    작성자 본인 또는 관리자만 가능합니다.

                    - 404 REFERRAL_NOT_FOUND: 의뢰가 없거나 권한 없음
                    - 409 INVALID_REFERRAL_STATUS: 삭제할 수 없는 상태
                    """)
    @DeleteMapping("/{referralId}")
    public ResponseEntity<ApiResponse<Void>> deleteReferral(
            @Parameter(description = "요청자 ID") @RequestHeader("X-User-Id") UUID userId,
            @Parameter(description = "요청자 권한") @RequestHeader("X-User-Role") RoleType role,
            @Parameter(description = "의뢰 ID") @PathVariable("referralId") UUID referralId
    ) {
        referralService.deleteReferral(userId, referralId, role);
        return ResponseEntity.ok(ApiResponse.ok("삭제가 완료되었습니다.", null));
    }
}
