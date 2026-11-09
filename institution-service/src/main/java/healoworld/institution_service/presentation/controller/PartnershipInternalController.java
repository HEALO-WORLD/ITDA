package healoworld.institution_service.presentation.controller;

import healoworld.institution_service.application.service.partnership.PartnershipService;
import healoworld.institution_service.presentation.dto.response.PartnerExamInstitutionsResponse;
import healoworld.institution_service.presentation.dto.response.PartnershipCheckResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/internal/partnerships")
public class PartnershipInternalController {
    private final PartnershipService partnershipService;

    @GetMapping("/check")
    public PartnershipCheckResponse check(@RequestParam UUID requesterInstitutionId,
                                          @RequestParam UUID examInstitutionId) {
        return new PartnershipCheckResponse(
                partnershipService.isActivePartnership(requesterInstitutionId, examInstitutionId));
    }

    @GetMapping("/exam-institutions")
    public PartnerExamInstitutionsResponse examInstitutions(@RequestParam UUID requesterInstitutionId) {
        return new PartnerExamInstitutionsResponse(
                partnershipService.getPartnerExamInstitutionIds(requesterInstitutionId));
    }
}
