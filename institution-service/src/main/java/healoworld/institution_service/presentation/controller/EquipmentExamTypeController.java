package healoworld.institution_service.presentation.controller;

import healoworld.institution_service.application.service.equipment.EquipmentExamTypeService;
import healoworld.institution_service.domain.enums.Role;
import healoworld.institution_service.global.dto.ApiResponse;
import healoworld.institution_service.global.dto.PagedResponse;
import healoworld.institution_service.presentation.dto.request.EquipmentExamTypeCreateRequest;
import healoworld.institution_service.presentation.dto.request.EquipmentExamTypeUpdateRequest;
import healoworld.institution_service.presentation.dto.response.EquipmentExamTypeResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/equipments/{equipmentId}/exam-types")
public class EquipmentExamTypeController {

    private final EquipmentExamTypeService equipmentExamTypeService;

    @PostMapping
    public ResponseEntity<ApiResponse<EquipmentExamTypeResponse>> createEquipmentExamType(
            @PathVariable("equipmentId") UUID equipmentId,
            @RequestHeader("X-User-Id") UUID userId,
            @RequestHeader("X-Institution-Id") UUID userInstitutionId,
            @RequestHeader("X-Role") Role userRole,
            @Valid @RequestBody EquipmentExamTypeCreateRequest request
    ) {
        EquipmentExamTypeResponse response = equipmentExamTypeService.createEquipmentExamType(
                equipmentId,
                request,
                userId,
                userInstitutionId,
                userRole
        );

        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok("장비의 지원 검사 유형이 등록되었습니다.", response));
    }

    @PatchMapping("/{examTypeId}")
    public ResponseEntity<ApiResponse<EquipmentExamTypeResponse>> updateEquipmentExamType(
            @PathVariable("equipmentId") UUID equipmentId,
            @PathVariable("examTypeId") UUID examTypeId,
            @RequestHeader("X-User-Id") UUID userId,
            @RequestHeader("X-Institution-Id") UUID userInstitutionId,
            @RequestHeader("X-Role") Role userRole,
            @Valid @RequestBody EquipmentExamTypeUpdateRequest request
    ) {
        EquipmentExamTypeResponse response = equipmentExamTypeService.updateEquipmentExamType(
                equipmentId, examTypeId, request, userId, userInstitutionId, userRole
        );

        return ResponseEntity.ok(ApiResponse.ok("장비별 검사 소요시간이 수정되었습니다.", response));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<PagedResponse<EquipmentExamTypeResponse>>> getEquipmentExamTypes(
            @PathVariable("equipmentId") UUID equipmentId,
            @RequestHeader("X-User-Id") UUID userId,
            @RequestHeader("X-Institution-Id") UUID userInstitutionId,
            @RequestHeader("X-Role") Role userRole,
            @RequestParam(name = "page", defaultValue = "0") Integer page,
            @RequestParam(name = "size", defaultValue = "20") Integer size
    ) {
        PagedResponse<EquipmentExamTypeResponse> response = equipmentExamTypeService.getEquipmentExamTypes(
                equipmentId,
                userId,
                userInstitutionId,
                userRole,
                page,
                size
        );

        return ResponseEntity.ok(ApiResponse.ok("장비의 지원 검사 유형 목록 조회에 성공했습니다.", response));
    }
}
