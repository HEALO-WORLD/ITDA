package healoworld.institution_service.presentation.controller;

import healoworld.institution_service.application.service.partnership.PartnershipService;
import healoworld.institution_service.domain.enums.PartnershipStatus;
import healoworld.institution_service.global.dto.ApiResponse;
import healoworld.institution_service.presentation.dto.response.MyPartnershipResponse;
import lombok.RequiredArgsConstructor;
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
}
