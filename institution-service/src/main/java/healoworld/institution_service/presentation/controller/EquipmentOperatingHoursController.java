package healoworld.institution_service.presentation.controller;

import healoworld.institution_service.application.service.equipment.EquipmentOperatingHoursService;
import healoworld.institution_service.domain.enums.Role;
import healoworld.institution_service.global.dto.ApiResponse;
import healoworld.institution_service.presentation.dto.request.EquipmentOperatingHoursUpdateRequest;
import healoworld.institution_service.presentation.dto.response.EquipmentOperatingHoursResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/equipments/{equipmentId}/operating-hours")
public class EquipmentOperatingHoursController {

    private final EquipmentOperatingHoursService operatingHoursService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<EquipmentOperatingHoursResponse>>> getOperatingHours(
            @PathVariable("equipmentId") UUID equipmentId,
            @RequestHeader("X-User-Id") UUID userId,
            @RequestHeader("X-Institution-Id") UUID userInstitutionId,
            @RequestHeader("X-Role") Role userRole
    ) {
        List<EquipmentOperatingHoursResponse> response = operatingHoursService.getOperatingHours(
                equipmentId,
                userId,
                userInstitutionId,
                userRole
        );

        return ResponseEntity.ok(ApiResponse.ok("장비 운영시간 조회에 성공했습니다.", response));
    }

    @PutMapping
    public ResponseEntity<ApiResponse<List<EquipmentOperatingHoursResponse>>> updateOperatingHours(
            @PathVariable("equipmentId") UUID equipmentId,
            @RequestHeader("X-User-Id") UUID userId,
            @RequestHeader("X-Institution-Id") UUID userInstitutionId,
            @RequestHeader("X-Role") Role userRole,
            @Valid @RequestBody EquipmentOperatingHoursUpdateRequest request
    ) {
        List<EquipmentOperatingHoursResponse> response = operatingHoursService.updateOperatingHours(
                equipmentId,
                request,
                userId,
                userInstitutionId,
                userRole
        );

        return ResponseEntity.ok(ApiResponse.ok("장비 운영시간이 설정되었습니다.", response));
    }
}