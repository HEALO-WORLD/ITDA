package healoworld.slot_service.presentation.controller;

import healoworld.slot_service.application.dto.HoldResult;
import healoworld.slot_service.application.service.SlotHoldService;
import healoworld.slot_service.global.dto.ApiResponse;
import healoworld.slot_service.global.exception.BaseException;
import healoworld.slot_service.global.exception.ErrorCode;
import healoworld.slot_service.presentation.dto.request.HoldRequest;
import healoworld.slot_service.presentation.dto.response.HoldResponse;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/slots")
public class SlotHoldController {

    private final SlotHoldService slotHoldService;

    @PostMapping("/{slotId}/hold")
    public ResponseEntity<ApiResponse<HoldResponse>> hold(
            @Parameter(description = "슬롯 ID") @PathVariable UUID slotId,
            @Parameter(description = "요청자 권한") @RequestHeader("X-Role") String role,
            @RequestBody @Valid HoldRequest request) {

        if (!"ADMIN".equals(role)) {
            throw new BaseException(ErrorCode.ADMIN_ONLY);
        }

        HoldResult result = slotHoldService.hold(slotId, request.getReservationId());

        if (!result.isSuccess()) {
            throw new BaseException(ErrorCode.SLOT_NOT_AVAILABLE);
        }

        HoldResponse response = HoldResponse.from(result);
        return ResponseEntity.status(HttpStatus.OK)
                .body(ApiResponse.ok("슬롯 임시 확보가 완료되었습니다.", response));
    }
}