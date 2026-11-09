package healoworld.institution_service.presentation.controller;

import healoworld.institution_service.application.service.institution.InstitutionService;
import healoworld.institution_service.domain.enums.Role;
import healoworld.institution_service.global.dto.ApiResponse;
import healoworld.institution_service.presentation.dto.request.InstitutionCreateRequest;
import healoworld.institution_service.presentation.dto.request.InstitutionUpdateRequest;
import healoworld.institution_service.presentation.dto.response.InstitutionResponse;
import healoworld.institution_service.presentation.dto.response.PublicInstitutionResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/institutions")
public class InstitutionController {
    private final InstitutionService institutionService;

    @GetMapping("/public")
    public ResponseEntity<ApiResponse<List<PublicInstitutionResponse>>> getPublicInstitutions() {
        return ResponseEntity.ok(ApiResponse.ok("가입 가능한 기관 목록입니다.",
                institutionService.getPublicInstitutions()));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<InstitutionResponse>> create(
            @Valid @RequestBody InstitutionCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("기관이 등록되었습니다.", institutionService.create(request)));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<InstitutionResponse>>> getInstitutions() {
        return ResponseEntity.ok(ApiResponse.ok("기관 목록입니다.", institutionService.getInstitutions()));
    }

    @PatchMapping("/{institutionId}")
    public ResponseEntity<ApiResponse<InstitutionResponse>> update(
            @PathVariable UUID institutionId,
            @Valid @RequestBody InstitutionUpdateRequest request) {
        return ResponseEntity.ok(ApiResponse.ok("기관 정보가 수정되었습니다.",
                institutionService.update(institutionId, request)));
    }

    @DeleteMapping("/{institutionId}")
    public ResponseEntity<ApiResponse<Void>> delete(
            @PathVariable UUID institutionId,
            @RequestHeader("X-User-Id") UUID adminId) {
        institutionService.delete(institutionId, adminId);
        return ResponseEntity.ok(ApiResponse.ok("기관이 삭제되었습니다.", null));
    }

    @GetMapping("/{institutionId}")
    public ResponseEntity<ApiResponse<InstitutionResponse>> getInstitutionDetail(
            @PathVariable UUID institutionId,
            @RequestHeader("X-Institution-Id") UUID myInstitutionId,
            @RequestHeader("X-Role") Role role) {
        return ResponseEntity.ok(ApiResponse.ok("기관 상세 정보입니다.",
                institutionService.getInstitutionDetail(institutionId, myInstitutionId, role)));
    }
}
