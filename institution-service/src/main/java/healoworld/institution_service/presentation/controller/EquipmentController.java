package healoworld.institution_service.presentation.controller;

import healoworld.institution_service.application.service.equipment.EquipmentService;
import healoworld.institution_service.domain.enums.Modality;
import healoworld.institution_service.domain.enums.Role;
import healoworld.institution_service.global.dto.ApiResponse;
import healoworld.institution_service.global.dto.PagedResponse;
import healoworld.institution_service.presentation.dto.request.EquipmentCreateRequest;
import healoworld.institution_service.presentation.dto.request.EquipmentUpdateRequest;
import healoworld.institution_service.presentation.dto.response.EquipmentCreateResponse;
import healoworld.institution_service.presentation.dto.response.EquipmentDetailResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1")
public class EquipmentController {

    private final EquipmentService equipmentService;

    @PostMapping("/institutions/{institutionId}/equipments")
    public ResponseEntity<ApiResponse<EquipmentCreateResponse>> createEquipment(
            @PathVariable("institutionId") UUID institutionId,
            @RequestHeader("X-User-Id") UUID userId,
            @RequestHeader("X-Institution-Id") UUID userInstitutionId,
            @RequestHeader("X-Role") Role userRole,
            @Valid @RequestBody EquipmentCreateRequest request
    ) {
        EquipmentCreateResponse response = equipmentService.createEquipment(
                institutionId,
                request,
                userId,
                userInstitutionId,
                userRole
        );

        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok("장비가 등록되었습니다.", response));}

    @GetMapping("/equipments/{equipmentId}")
    public ResponseEntity<ApiResponse<EquipmentDetailResponse>> getEquipment(
            @PathVariable("equipmentId") UUID equipmentId,
            @RequestHeader("X-User-Id") UUID userId,
            @RequestHeader("X-Institution-Id") UUID userInstitutionId,
            @RequestHeader("X-Role") Role userRole
    ) {
        EquipmentDetailResponse response = equipmentService.getEquipment(
                equipmentId,
                userId,
                userInstitutionId,
                userRole
        );

        return ResponseEntity.ok(ApiResponse.ok("장비 조회에 성공했습니다.", response));
    }

    @GetMapping("/institutions/{institutionId}/equipments")
    public ResponseEntity<ApiResponse<PagedResponse<EquipmentDetailResponse>>> getEquipments(
            @PathVariable("institutionId") UUID institutionId,
            @RequestHeader("X-User-Id") UUID userId,
            @RequestHeader("X-Institution-Id") UUID userInstitutionId,
            @RequestHeader("X-Role") Role userRole,
            @RequestParam(name = "modality", required = false) Modality modality,
            @RequestParam(name = "page", defaultValue = "0") Integer page,
            @RequestParam(name = "size", defaultValue = "20") Integer size
    ) {
        PagedResponse<EquipmentDetailResponse> response = equipmentService.getEquipments(
                institutionId,
                userId,
                userInstitutionId,
                userRole,
                modality,
                page,
                size
        );

        return ResponseEntity.ok(ApiResponse.ok("장비 목록 조회에 성공했습니다.", response));
    }

    @PatchMapping("/equipments/{equipmentId}")
    public ResponseEntity<ApiResponse<EquipmentDetailResponse>> updateEquipment(
            @PathVariable("equipmentId") UUID equipmentId,
            @RequestHeader("X-User-Id") UUID userId,
            @RequestHeader("X-Institution-Id") UUID userInstitutionId,
            @RequestHeader("X-Role") Role userRole,
            @Valid @RequestBody EquipmentUpdateRequest request
    ) {
        EquipmentDetailResponse response = equipmentService.updateEquipment(
                equipmentId,
                request,
                userId,
                userInstitutionId,
                userRole
        );

        return ResponseEntity.ok(ApiResponse.ok("장비 정보가 수정되었습니다.", response));
    }
}