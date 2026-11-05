package healoworld.institution_service.presentation.controller;

import healoworld.institution_service.application.service.equipment.EquipmentSlotContextService;
import healoworld.institution_service.global.dto.ApiResponse;
import healoworld.institution_service.presentation.dto.response.EquipmentSlotContextResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/internal/equipments")
public class EquipmentInternalController {

    private final EquipmentSlotContextService slotContextService;

    @GetMapping("/{equipmentId}/slot-context")
    public ResponseEntity<ApiResponse<EquipmentSlotContextResponse>> getSlotContext(
            @PathVariable("equipmentId") UUID equipmentId,
            @RequestParam("examTypeId") UUID examTypeId,
            @RequestParam("date")
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date
    ) {
        EquipmentSlotContextResponse response = slotContextService.getSlotContext(equipmentId, examTypeId, date);

        return ResponseEntity.ok(ApiResponse.ok("슬롯 생성 기준정보 조회에 성공했습니다.", response));
    }
}