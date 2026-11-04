package healoworld.slot_service.presentation.controller;

import healoworld.slot_service.application.dto.ReleaseOutcome;
import healoworld.slot_service.application.service.SlotReleaseService;
import healoworld.slot_service.application.service.SlotStatusHistoryService;
import healoworld.slot_service.domain.enums.SlotStatus;
import healoworld.slot_service.global.dto.ApiResponse;
import healoworld.slot_service.global.dto.PagedResponse;
import healoworld.slot_service.global.exception.BaseException;
import healoworld.slot_service.global.exception.ErrorCode;
import healoworld.slot_service.presentation.dto.request.SlotReleaseRequest;
import healoworld.slot_service.presentation.dto.request.SlotStatusHistorySearchRequest;
import healoworld.slot_service.presentation.dto.response.SlotReleaseResponse;
import healoworld.slot_service.presentation.dto.response.SlotStatusHistoryResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

// ADMIN 전용 슬롯 API. 게이트웨이 정책의 [ADMIN] 그룹과 대응한다
@Tag(name = "Slot Admin", description = "슬롯 관리자 API (강제 해제, 상태 이력 조회)")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/slots")
public class SlotAdminController {

    private final SlotReleaseService slotReleaseService;
    private final SlotStatusHistoryService slotStatusHistoryService;

    @Operation(
            summary = "[ADMIN] 슬롯 강제 해제",
            description = """
                    지정한 예약이 잡고 있는 슬롯을 강제로 해제합니다.

                    일반 흐름의 해제는 Kafka(ReleaseHold, ReleaseSlot)로 처리하며,
                    이 API는 Referral과 Slot의 상태가 어긋났을 때 관리자가 정리하는 용도입니다.
                    (예: "확정된 슬롯에 ReleaseHold 도착" 경고 로그)

                    - ADMIN 역할만 호출할 수 있습니다.
                    - 슬롯이 요청한 예약으로 HELD 또는 BOOKED면 AVAILABLE로 반환합니다.
                    - 장애로 UNAVAILABLE이면 상태는 유지하고 예약 연결만 해제합니다.
                    - Referral의 예약·의뢰 상태는 바꾸지 않으며 이벤트도 발행하지 않습니다.
                      Referral에서 이미 실패·취소된 예약인지 먼저 확인하세요.
                    - 슬롯 상태 이력에 ADMIN_RELEASE 사유와 요청한 관리자 ID(X-User-Id)를 남깁니다.

                    오류 응답:
                    - 400 VALIDATION_FAILED: reservationId 누락
                    - 400 MISSING_HEADER: 권한 헤더 또는 사용자 ID 헤더 누락
                    - 400 INVALID_INPUT: 잘못된 UUID 또는 요청 본문
                    - 403 ADMIN_ONLY: ADMIN이 아님
                    - 404 SLOT_NOT_FOUND: 슬롯 없음
                    - 409 SLOT_NOT_RELEASABLE: 요청한 예약으로 잡혀 있는 슬롯이 아님 (이미 해제됨, 다른 예약 등)
                    """
    )
    @PostMapping("/{slotId}/release")
    public ResponseEntity<ApiResponse<SlotReleaseResponse>> release(
            @Parameter(description = "슬롯 ID") @PathVariable UUID slotId,
            @Parameter(description = "요청자 ID") @RequestHeader("X-User-Id") UUID userId,
            @Parameter(description = "요청자 권한") @RequestHeader("X-Role") String role,
            @RequestBody @Valid SlotReleaseRequest request) {

        requireAdmin(role);

        ReleaseOutcome outcome = slotReleaseService.forceRelease(slotId, request.getReservationId(), userId);

        SlotStatus status = outcome == ReleaseOutcome.DETACHED ? SlotStatus.UNAVAILABLE : SlotStatus.AVAILABLE;
        SlotReleaseResponse response = new SlotReleaseResponse(slotId, request.getReservationId(), outcome, status);
        return ResponseEntity.ok(ApiResponse.ok("슬롯 강제 해제가 완료되었습니다.", response));
    }

    @Operation(
            summary = "[ADMIN] 슬롯 상태 이력 조회",
            description = """
                    슬롯의 상태 변경 이력을 오래된 순서로 조회합니다.
                    Referral과 Slot의 상태 불일치 추적, 강제 해제 전 확인 등 운영 용도입니다.

                    - ADMIN 역할만 호출할 수 있습니다.
                    - reason: HOLD, BOOK, HOLD_EXPIRED, RELEASE_HOLD, RELEASE_SLOT, ADMIN_RELEASE
                    - Kafka 이벤트로 바뀐 경우 sourceEventId, 관리자 API로 바뀐 경우 changedBy가 채워지고,
                      HOLD 만료 스케줄러로 바뀐 경우 둘 다 NULL입니다.
                    - 장애 슬롯의 예약 연결만 해제된 경우 fromStatus와 toStatus가 모두 UNAVAILABLE입니다.

                    오류 응답:
                    - 400 VALIDATION_FAILED: page < 0, size가 1~100 범위 밖
                    - 400 MISSING_HEADER: 권한 헤더 누락
                    - 400 INVALID_INPUT: 잘못된 UUID
                    - 403 ADMIN_ONLY: ADMIN이 아님
                    - 404 SLOT_NOT_FOUND: 슬롯이 없거나 삭제됨
                    """
    )
    @GetMapping("/{slotId}/status-histories")
    public ResponseEntity<ApiResponse<PagedResponse<SlotStatusHistoryResponse>>> getStatusHistories(
            @Parameter(description = "슬롯 ID") @PathVariable UUID slotId,
            @Parameter(description = "요청자 권한") @RequestHeader("X-Role") String role,
            @Valid @ModelAttribute SlotStatusHistorySearchRequest request) {

        requireAdmin(role);

        PagedResponse<SlotStatusHistoryResponse> response =
                slotStatusHistoryService.getHistories(slotId, request.getPage(), request.getSize());
        return ResponseEntity.ok(ApiResponse.ok("슬롯 상태 이력 조회에 성공했습니다.", response));
    }

    private void requireAdmin(String role) {
        if (!"ADMIN".equals(role)) {
            throw new BaseException(ErrorCode.ADMIN_ONLY);
        }
    }
}
