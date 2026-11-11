package healoworld.referral_service.presentation.controller;

import healoworld.referral_service.application.service.ReservationService;
import healoworld.referral_service.domain.enums.RoleType;
import healoworld.referral_service.global.dto.ApiResponse;
import healoworld.referral_service.global.dto.ErrorResponse;
import healoworld.referral_service.global.dto.PagedResponse;
import healoworld.referral_service.presentation.dto.request.ReservationSearchCondition;
import healoworld.referral_service.presentation.dto.response.ReservationResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
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
                    의뢰기관·검사기관 소속 사용자 또는 관리자가 예약 상세를 조회합니다.
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "MISSING_HEADER, INVALID_INPUT",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "RESERVATION_NOT_FOUND",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
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
                    내 기관이 의뢰했거나 검사를 맡은 예약을 조회합니다. 관리자는 전체 조회가 가능하며 의뢰 ID로 필터링할 수 있습니다.
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = """
                    - MISSING_HEADER: X-Institution-Id·X-Role 헤더 누락
                    - INVALID_INPUT: 헤더의 UUID 형식 또는 역할 값이 잘못됨
                    - VALIDATION_FAILED: 검색 조건의 값 형식이 잘못됨
                    """, content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping
    public ResponseEntity<ApiResponse<PagedResponse<ReservationResponse>>> getReservations(
            @Parameter(description = "요청자 소속 기관 ID") @RequestHeader("X-Institution-Id") UUID institutionId,
            @Parameter(description = "요청자 권한") @RequestHeader("X-Role") RoleType role,
            @ParameterObject @ModelAttribute ReservationSearchCondition cond
    ) {
        PagedResponse<ReservationResponse> response = reservationService.getReservations(institutionId, cond, role);
        return ResponseEntity.ok(ApiResponse.ok("예약 목록 조회가 완료되었습니다.", response));
    }

    // 예약 삭제 API(DELETE /{reservationId})는 제공하지 않는다 (예약 이력 보존). 요청하면 Spring이 405로 응답한다
    // 예약을 끝내려면 아래 취소 API를 사용한다

    @Operation(summary = "예약 취소",
            description = """
                    의뢰기관이 검사 시작 2시간 전까지 현재 확정 예약을 취소합니다. 검사 시작 전(SCHEDULED)에만 가능하며 슬롯 해제는 Kafka로 비동기 요청합니다.
                    """)
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "취소 완료 (data는 null)"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = """
                    - MISSING_HEADER: X-User-Id·X-Institution-Id·X-Role 헤더 누락
                    - INVALID_INPUT: UUID 형식 또는 역할 값이 잘못됨
                    """, content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = """
                    - RESERVATION_NOT_FOUND: 예약이 없거나, 의뢰를 만든 기관·ADMIN이 아님
                    - REFERRAL_NOT_FOUND: 예약의 의뢰가 없음
                    """, content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = """
                    - RESERVATION_CANCEL_DEADLINE_PASSED: 예약 시작 2시간 전이 지남
                    - INVALID_RESERVATION_STATUS: 의뢰의 현재 예약이 아님, 예약이 CONFIRMED가 아님(이미 취소 등), 검사가 없거나 다른 예약에 연결됨
                    - EXAMINATION_CANCEL_NOT_ALLOWED: 검사가 이미 시작(IN_PROGRESS)되었거나 완료(COMPLETED)됨
                    - INVALID_REFERRAL_STATUS: 의뢰가 CONFIRMED·NEEDS_RESCHEDULE이 아님
                    """, content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PatchMapping("/{reservationId}/cancel")
    public ResponseEntity<ApiResponse<Void>> cancelReservation(
            @Parameter(description = "요청자 ID") @RequestHeader("X-User-Id") UUID userId,
            @Parameter(description = "요청자 소속 기관 ID") @RequestHeader("X-Institution-Id") UUID institutionId,
            @Parameter(description = "요청자 권한") @RequestHeader("X-Role") RoleType role,
            @Parameter(description = "예약 ID") @PathVariable("reservationId") UUID reservationId
    ) {
        reservationService.cancelReservation(userId, institutionId, role, reservationId);
        return ResponseEntity.ok(ApiResponse.ok("취소가 완료되었습니다.", null));
    }
}
