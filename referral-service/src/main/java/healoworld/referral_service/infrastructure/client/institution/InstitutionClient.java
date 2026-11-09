package healoworld.referral_service.infrastructure.client.institution;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.UUID;

@FeignClient(name = "institution-service")
public interface InstitutionClient {
    @GetMapping("/api/v1/internal/partnerships/check")
    PartnershipCheckResponse check(@RequestParam("requesterInstitutionId") UUID requesterInstitutionId,
                                   @RequestParam("examInstitutionId") UUID examInstitutionId);

}
