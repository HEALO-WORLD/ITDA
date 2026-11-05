package healoworld.institution_service.presentation.controller;

import healoworld.institution_service.application.service.equipment.EquipmentSlotContextService;
import healoworld.institution_service.application.service.equipment.EquipmentVisibilityService;
import healoworld.institution_service.domain.enums.Role;
import healoworld.institution_service.global.dto.ApiResponse;
import healoworld.institution_service.presentation.dto.response.EquipmentSlotContextResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/internal/equipments")
public class EquipmentInternalController {

    private final EquipmentSlotContextService slotContextService;
    private final EquipmentVisibilityService equipmentVisibilityService;

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

    @GetMapping("/visible")
    public ResponseEntity<ApiResponse<Set<UUID>>> getVisibleEquipmentIds(
            @RequestParam("callerInstitutionId") UUID callerInstitutionId,
            @RequestParam("role") Role role,
            @RequestParam(name = "institutionId", required = false)
            UUID institutionId,
            @RequestParam(name = "onlyOperational", defaultValue = "true")
            boolean onlyOperational
    ) {
        Set<UUID> ids = equipmentVisibilityService.getVisibleEquipmentIds(
                callerInstitutionId,
                role,
                institutionId,
                onlyOperational
        );

        return ResponseEntity.ok(
                ApiResponse.ok("조회 가능한 장비 ID 조회에 성공했습니다.", ids)
        );
    }
}