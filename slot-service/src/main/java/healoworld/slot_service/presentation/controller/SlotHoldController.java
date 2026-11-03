package healoworld.slot_service.presentation.controller;

import healoworld.slot_service.application.dto.HoldResult;
import healoworld.slot_service.application.service.SlotHoldService;
import healoworld.slot_service.global.dto.ApiResponse;
import healoworld.slot_service.global.exception.BaseException;
import healoworld.slot_service.global.exception.ErrorCode;
import healoworld.slot_service.presentation.dto.request.SlotHoldRequest;
import healoworld.slot_service.presentation.dto.response.SlotHoldResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@Tag(name = "Slot Hold", description = "슬롯 임시 확보(HOLD) API")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/slots")
public class SlotHoldController {

    private final SlotHoldService slotHoldService;

    @Operation(
            summary = "[ADMIN] 슬롯 임시 확보",
            description = """
                    AVAILABLE 슬롯을 지정한 예약으로 5분간 HELD 상태로 확보합니다.

                    일반 예약 흐름의 HOLD는 Kafka(HoldRequested)로 처리하며,
                    이 API는 관리자 수동 처리용입니다.

                    - ADMIN 역할만 호출할 수 있습니다.
                    - 같은 예약으로 이미 HELD인 슬롯에 다시 요청하면 만료 시각을 연장하지 않고 기존 만료 시각으로 성공을 반환합니다.

                    오류 응답:
                    - 400 VALIDATION_FAILED: reservationId 누락
                    - 400 MISSING_HEADER: 권한 헤더 누락
                    - 400 INVALID_INPUT: 잘못된 UUID 또는 요청 본문
                    - 403 ADMIN_ONLY: ADMIN이 아님
                    - 404 SLOT_NOT_FOUND: 슬롯 없음
                    - 409 SLOT_NOT_AVAILABLE: 다른 예약이 확보했거나 BOOKED·UNAVAILABLE 상태
                    """
    )
    @PostMapping("/{slotId}/hold")
    public ResponseEntity<ApiResponse<SlotHoldResponse>> hold(
            @Parameter(description = "슬롯 ID") @PathVariable UUID slotId,
            @Parameter(description = "요청자 권한") @RequestHeader("X-Role") String role,
            @RequestBody @Valid SlotHoldRequest request) {

        if (!"ADMIN".equals(role)) {
            throw new BaseException(ErrorCode.ADMIN_ONLY);
        }

        HoldResult result = slotHoldService.hold(slotId, request.getReservationId());

        if (!result.isSuccess()) {
            throw new BaseException(ErrorCode.SLOT_NOT_AVAILABLE);
        }

        SlotHoldResponse response = SlotHoldResponse.from(result);
        return ResponseEntity.status(HttpStatus.OK)
                .body(ApiResponse.ok("슬롯 임시 확보가 완료되었습니다.", response));
    }
}