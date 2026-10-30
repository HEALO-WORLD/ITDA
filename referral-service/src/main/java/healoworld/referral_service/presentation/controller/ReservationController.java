package healoworld.referral_service.presentation.controller;

import healoworld.referral_service.application.service.ReservationService;
import healoworld.referral_service.domain.enums.RoleType;
import healoworld.referral_service.global.dto.ApiResponse;
import healoworld.referral_service.global.dto.PagedResponse;
import healoworld.referral_service.presentation.dto.request.CreateReservationRequest;
import healoworld.referral_service.presentation.dto.request.ReservationSearchCondition;
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

import java.util.UUID;

@Tag(name = "Reservation", description = "검사 예약 API")
@RestController
@RequestMapping("/api/v1/referrals/reservations")
@RequiredArgsConstructor
public class ReservationController {
    public final ReservationService reservationService;

    @Operation(summary = "예약 단건 조회",
            description = """
                    의뢰기관 또는 검사기관 소속, 관리자만 조회할 수 있습니다.

                    - 404 RESERVATION_NOT_FOUND: 예약이 없거나 권한 없음
                    """)
    @GetMapping("/{reservationId}")
    public ResponseEntity<ApiResponse<ReservationResponse>> getReservation(
            @Parameter(description = "요청자 소속 기관 ID") @RequestHeader("X-Institution-Id") UUID institutionId,
            @Parameter(description = "요청자 권한") @RequestHeader("X-Role") RoleType role,
            @Parameter(description = "예약 ID") @PathVariable("reservationId") UUID reservationId
    ) {
        ReservationResponse response = reservationService.getReservation(institutionId, reservationId, role);
        return ResponseEntity.ok(ApiResponse.ok("예약 조회가 완료되었습니다.", response));
    }

    @Operation(summary = "예약 목록 조회",
            description = """
                    본인 기관이 의뢰했거나 본인 기관에서 검사하는 예약만 조회됩니다. 관리자는 전체를 조회합니다.
                    한 의뢰의 예약 이력을 보려면 referralId를 넣습니다.
                    """)
    @GetMapping
    public ResponseEntity<ApiResponse<PagedResponse<ReservationResponse>>> getReservations(
            @Parameter(description = "요청자 소속 기관 ID") @RequestHeader("X-Institution-Id") UUID institutionId,
            @Parameter(description = "요청자 권한") @RequestHeader("X-Role") RoleType role,
            @ParameterObject @ModelAttribute ReservationSearchCondition cond
    ) {
        PagedResponse<ReservationResponse> response = reservationService.getReservations(institutionId, cond, role);
        return ResponseEntity.ok(ApiResponse.ok("예약 목록 조회가 완료되었습니다.", response));
    }

    @Operation(summary = "예약 삭제",
            description = """
                    CANCELED, BLOCKED, FAILED 상태의 예약만 soft delete 합니다.
                    의뢰 작성자 본인 또는 관리자만 가능합니다.

                    - 404 RESERVATION_NOT_FOUND: 예약이 없거나 권한 없음
                    - 409 INVALID_RESERVATION_STATUS: 삭제할 수 없는 상태
                    """)
    @DeleteMapping("/{reservationId}")
    public ResponseEntity<ApiResponse<Void>> deleteReservation(
            @Parameter(description = "요청자 ID") @RequestHeader("X-User-Id") UUID userId,
            @Parameter(description = "요청자 권한") @RequestHeader("X-Role") RoleType role,
            @Parameter(description = "예약 ID") @PathVariable("reservationId") UUID reservationId
    ) {
        reservationService.deleteReservation(userId, reservationId, role);
        return ResponseEntity.ok(ApiResponse.ok("삭제가 완료되었습니다.",null));
    }
}
