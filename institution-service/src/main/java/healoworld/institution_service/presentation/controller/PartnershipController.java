package healoworld.institution_service.presentation.controller;

import healoworld.institution_service.application.service.partnership.PartnershipService;
import healoworld.institution_service.domain.enums.PartnershipStatus;
import healoworld.institution_service.domain.enums.Role;
import healoworld.institution_service.global.dto.ApiResponse;
import healoworld.institution_service.presentation.dto.request.PartnershipCreateRequest;
import healoworld.institution_service.presentation.dto.response.MyPartnershipResponse;
import healoworld.institution_service.presentation.dto.response.PartnershipResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/partnerships")
public class PartnershipController {
    private final PartnershipService partnershipService;

    @GetMapping("/me")
    public ResponseEntity<ApiResponse<List<MyPartnershipResponse>>> getMyPartnerShips(
            @RequestHeader("X-Institution-Id") UUID institutionId,
            @RequestParam(required = false) PartnershipStatus status
    ) {
        return ResponseEntity.ok(ApiResponse.ok("내 기관 협약 목록입니다.",
                partnershipService.getMyPartnerships(institutionId, status)));

    }

    @PostMapping
    public ResponseEntity<ApiResponse<PartnershipResponse>> request(
            @RequestHeader("X-Institution-Id") UUID myInstitutionId,
            @Valid @RequestBody PartnershipCreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("협약을 요청했습니다.",
                        partnershipService.request(myInstitutionId, request.examInstitutionId())));
    }

    @PatchMapping("/{partnershipId}/approve")
    public ResponseEntity<ApiResponse<PartnershipResponse>> approve(
            @PathVariable UUID partnershipId,
            @RequestHeader("X-Institution-Id") UUID myInstitutionId) {
        return ResponseEntity.ok(ApiResponse.ok("협약을 승인했습니다.",
                partnershipService.approve(partnershipId, myInstitutionId)));
    }

    @PatchMapping("/{partnershipId}/reject")
    public ResponseEntity<ApiResponse<PartnershipResponse>> reject(
            @PathVariable UUID partnershipId,
            @RequestHeader("X-Institution-Id") UUID myInstitutionId) {
        return ResponseEntity.ok(ApiResponse.ok("협약을 거절했습니다.",
                partnershipService.reject(partnershipId, myInstitutionId)));
    }

    @PatchMapping("/{partnershipId}/terminate")
    public ResponseEntity<ApiResponse<PartnershipResponse>> terminate(
            @PathVariable UUID partnershipId,
            @RequestHeader("X-Institution-Id") UUID myInstitutionId,
            @RequestHeader("X-Role") Role role) {
        return ResponseEntity.ok(ApiResponse.ok("협약을 종료했습니다.",
                partnershipService.terminate(partnershipId, myInstitutionId, role)));
    }
}
