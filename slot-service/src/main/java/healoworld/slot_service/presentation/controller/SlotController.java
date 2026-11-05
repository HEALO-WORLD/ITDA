package healoworld.slot_service.presentation.controller;

import healoworld.slot_service.application.service.SlotService;
import healoworld.slot_service.global.dto.ApiResponse;
import healoworld.slot_service.presentation.dto.request.SlotCreateRequest;
import healoworld.slot_service.presentation.dto.request.SlotGenerateRequest;
import healoworld.slot_service.presentation.dto.response.SlotCreateResponse;
import healoworld.slot_service.presentation.dto.response.SlotDetailResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/slots")
@RequiredArgsConstructor
public class SlotController {

    private final SlotService slotService;

    @PostMapping
    public ResponseEntity<ApiResponse<SlotCreateResponse>> createSlot(
            @RequestHeader("X-User-Id") UUID userId,
            @RequestHeader("X-Institution-Id") UUID userInstitutionId,
            @RequestHeader("X-Role") String userRole,
            @Valid @RequestBody SlotCreateRequest request
    ) {
        SlotCreateResponse response = slotService.createSlot(
                request,
                userId,
                userInstitutionId,
                userRole
        );

        URI location = URI.create("/api/v1/slots/" + response.getId());

        return ResponseEntity.created(location).body(ApiResponse.ok("슬롯이 등록되었습니다.", response));
    }

    @GetMapping("/{slotId}")
    public ResponseEntity<ApiResponse<SlotDetailResponse>> getSlot(
            @PathVariable("slotId") UUID slotId
    ) {
        SlotDetailResponse response = slotService.getSlot(slotId);

        return ResponseEntity.ok(ApiResponse.ok("슬롯 조회에 성공했습니다.", response));
    }

    @PostMapping("/generate")
    public ResponseEntity<ApiResponse<List<SlotCreateResponse>>> generateSlots(
            @RequestHeader("X-User-Id") UUID userId,
            @RequestHeader("X-Institution-Id") UUID userInstitutionId,
            @RequestHeader("X-Role") String userRole,
            @Valid @RequestBody SlotGenerateRequest request
    ) {
        List<SlotCreateResponse> responses = slotService.generateSlots(
                request,
                userId,
                userInstitutionId,
                userRole
        );

        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok("슬롯이 일괄 등록되었습니다.", responses));
    }
}