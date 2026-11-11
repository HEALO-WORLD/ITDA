package healoworld.institution_service.presentation.controller;

import healoworld.institution_service.application.service.equipment.EquipmentStatusService;
import healoworld.institution_service.domain.enums.Role;
import healoworld.institution_service.global.dto.ApiResponse;
import healoworld.institution_service.global.dto.PagedResponse;
import healoworld.institution_service.presentation.dto.request.EquipmentStatusUpdateRequest;
import healoworld.institution_service.presentation.dto.response.EquipmentDetailResponse;
import healoworld.institution_service.presentation.dto.response.EquipmentStatusHistoryResponse;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/equipments/{equipmentId}")
public class EquipmentStatusController {
    private final EquipmentStatusService equipmentStatusService;

    @PatchMapping("/status")
    public ResponseEntity<ApiResponse<EquipmentDetailResponse>> updateStatus(
            @PathVariable("equipmentId") UUID equipmentId,
            @RequestHeader("X-User-Id") UUID userId,
            @RequestHeader("X-Institution-Id") UUID userInstitutionId,
            @RequestHeader("X-Role") Role role,
            @Valid @RequestBody EquipmentStatusUpdateRequest request) {
        EquipmentDetailResponse response = equipmentStatusService.updateStatus(
                equipmentId, request, userId, userInstitutionId, role);
        return ResponseEntity.ok(ApiResponse.ok("장비 상태 요청 처리가 완료되었습니다.", response));
    }

    @GetMapping("/status-histories")
    public ResponseEntity<ApiResponse<PagedResponse<EquipmentStatusHistoryResponse>>> getHistories(
            @PathVariable("equipmentId") UUID equipmentId,
            @RequestHeader("X-User-Id") UUID userId,
            @RequestHeader("X-Institution-Id") UUID userInstitutionId,
            @RequestHeader("X-Role") Role role,
            @RequestParam(name = "page", defaultValue = "0") Integer page,
            @RequestParam(name = "size", defaultValue = "20") Integer size) {
        var response = equipmentStatusService.getHistories(equipmentId, userId, userInstitutionId, role, page, size);
        return ResponseEntity.ok(ApiResponse.ok("장비 상태 이력 조회에 성공했습니다.", response));
    }
}
