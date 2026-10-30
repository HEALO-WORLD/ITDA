package healoworld.referral_service.presentation.controller;

import healoworld.referral_service.application.service.ReservationService;
import healoworld.referral_service.domain.enums.RoleType;
import healoworld.referral_service.global.dto.ApiResponse;
import healoworld.referral_service.global.dto.PagedResponse;
import healoworld.referral_service.presentation.dto.request.CreateReservationRequest;
import healoworld.referral_service.presentation.dto.request.ReservationSearchCondition;
import healoworld.referral_service.presentation.dto.response.ReservationResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/referrals/reservations")
@RequiredArgsConstructor
public class ReservationController {
    public final ReservationService reservationService;

    @GetMapping("/{reservationId}")
    public ResponseEntity<ApiResponse<ReservationResponse>> getReservation(
            @RequestHeader("X-User-Id") UUID userId,
            @RequestHeader("X-User-Role") RoleType role,
            @PathVariable("reservationId") UUID reservationId
    ) {
        ReservationResponse response = reservationService.getReservation(userId, reservationId, role);
        return ResponseEntity.ok(ApiResponse.ok("예약 조회가 완료되었습니다.", response));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<PagedResponse<ReservationResponse>>> getReservations(
            @RequestHeader("X-User-Id") UUID userId,
            @RequestHeader("X-User-Role") RoleType role,
            @ModelAttribute ReservationSearchCondition cond
    ) {
        PagedResponse<ReservationResponse> response = reservationService.getReservations(userId, cond, role);
        return ResponseEntity.ok(ApiResponse.ok("예약 목록 조회가 완료되었습니다.", response));
    }

    @DeleteMapping("/{reservationId}")
    public ResponseEntity<ApiResponse<Void>> deleteReservation(
            @RequestHeader("X-User-Id") UUID userId,
            @RequestHeader("X-User-Role") RoleType role,
            @PathVariable("reservationId") UUID reservationId
    ) {
        reservationService.deleteReservation(userId, reservationId, role);
        return ResponseEntity.ok(ApiResponse.ok("삭제가 완료되었습니다.",null));
    }
}
