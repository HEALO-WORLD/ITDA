package healoworld.slot_service.presentation.controller;

import healoworld.slot_service.application.dto.ReleaseOutcome;
import healoworld.slot_service.application.service.SlotReleaseService;
import healoworld.slot_service.application.service.SlotStatusHistoryService;
import healoworld.slot_service.domain.enums.SlotStatus;
import healoworld.slot_service.global.dto.ApiResponse;
import healoworld.slot_service.global.dto.ErrorResponse;
import healoworld.slot_service.global.dto.PagedResponse;
import healoworld.slot_service.global.exception.BaseException;
import healoworld.slot_service.global.exception.ErrorCode;
import healoworld.slot_service.presentation.dto.request.SlotReleaseRequest;
import healoworld.slot_service.presentation.dto.request.SlotStatusHistorySearchRequest;
import healoworld.slot_service.presentation.dto.response.SlotReleaseResponse;
import healoworld.slot_service.presentation.dto.response.SlotStatusHistoryResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
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
                    관리자가 예약에 연결된 슬롯을 강제 해제합니다. 중단 중인 슬롯은 UNAVAILABLE로 유지하며 의뢰·예약 상태는 변경하지 않습니다.
                    """
    )
    // 코드별 사유는 위 description의 "오류 응답" 목록에 있다. 여기서는 상태 코드와 ErrorResponse 구조만 연결한다
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "해제 완료"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "VALIDATION_FAILED, MISSING_HEADER, INVALID_INPUT",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "ADMIN_ONLY",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "SLOT_NOT_FOUND",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "SLOT_NOT_RELEASABLE",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/{slotId}/release")
    public ResponseEntity<ApiResponse<SlotReleaseResponse>> release(
            @Parameter(description = "슬롯 ID") @PathVariable UUID slotId,
            @Parameter(description = "요청자 ID") @RequestHeader("X-User-Id") UUID userId,
            @Parameter(description = "요청자 권한") @RequestHeader("X-Role") String role,
            @RequestBody @Valid SlotReleaseRequest request) {

        requireAdmin(role);

        ReleaseOutcome outcome = slotReleaseService.forceRelease(slotId, request.getReservationId(), userId);

        SlotStatus status = outcome == ReleaseOutcome.DETACHED || outcome == ReleaseOutcome.RELEASED_UNAVAILABLE
                ? SlotStatus.UNAVAILABLE : SlotStatus.AVAILABLE;
        SlotReleaseResponse response = new SlotReleaseResponse(slotId, request.getReservationId(), outcome, status);
        return ResponseEntity.ok(ApiResponse.ok("슬롯 강제 해제가 완료되었습니다.", response));
    }

    @Operation(
            summary = "[ADMIN] 슬롯 상태 이력 조회",
            description = """
                    슬롯 상태 변경 이력을 시간순으로 조회합니다. 예약·장비 장애 정합성 확인을 위한 관리자 API입니다.
                    """
    )
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "조회 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "VALIDATION_FAILED, MISSING_HEADER, INVALID_INPUT",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "ADMIN_ONLY",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "SLOT_NOT_FOUND",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/{slotId}/status-histories")
    public ResponseEntity<ApiResponse<PagedResponse<SlotStatusHistoryResponse>>> getStatusHistories(
            @Parameter(description = "슬롯 ID") @PathVariable UUID slotId,
            @Parameter(description = "요청자 권한") @RequestHeader("X-Role") String role,
            @ParameterObject @Valid @ModelAttribute SlotStatusHistorySearchRequest request) {

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
