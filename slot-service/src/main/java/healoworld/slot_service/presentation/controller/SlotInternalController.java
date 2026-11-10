package healoworld.slot_service.presentation.controller;

import healoworld.slot_service.application.service.SlotStatusQueryService;
import healoworld.slot_service.presentation.dto.request.SlotStatusQueryRequest;
import healoworld.slot_service.presentation.dto.response.SlotStatusResponse;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

// 서비스 간 내부 API (외부 요청은 Gateway의 block-internal 경로 규칙이 /api/v1/internal/**를 막는다)
// Referral의 예약-슬롯 정합성 점검(SlotClient.getSlotStatuses)이 호출한다
@RestController
@RequestMapping("/api/v1/internal/slots")
@RequiredArgsConstructor
public class SlotInternalController {

    private final SlotStatusQueryService slotStatusQueryService;

    // 응답은 ApiResponse로 감싸지 않은 JSON 배열이다.
    // 호출하는 Referral SlotClient의 반환형이 List<SlotStatusResponse>이고 별도 디코더가 없어 배열을 그대로 받기 때문이다
    // (기존 Feign 계약을 바꾸지 않는다). 오류 응답은 다른 API와 같은 ErrorResponse다
    @Operation(summary = "슬롯 상태 일괄 조회 (내부)",
            description = "여러 슬롯의 상태·점유 예약·HOLD 만료 시각을 한 번에 조회한다. 삭제됐거나 없는 슬롯은 응답에서 빠진다.")
    @PostMapping("/statuses")
    public ResponseEntity<List<SlotStatusResponse>> getStatuses(@Valid @RequestBody SlotStatusQueryRequest request) {
        return ResponseEntity.ok(slotStatusQueryService.getStatuses(request.slotIds()));
    }
}
