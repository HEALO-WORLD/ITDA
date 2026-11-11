package healoworld.referral_service.infrastructure.client.institution;

import healoworld.referral_service.global.dto.ApiResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.UUID;

@FeignClient(name = "institution-service")
public interface InstitutionClient {
    @GetMapping("/api/v1/internal/partnerships/check")
    PartnershipCheckResponse check(@RequestParam("requesterInstitutionId") UUID requesterInstitutionId,
                                   @RequestParam("examInstitutionId") UUID examInstitutionId);

    @GetMapping("/api/v1/internal/equipments/{equipmentId}/owner")
    ApiResponse<EquipmentOwnerResponse> getEquipmentOwner(@PathVariable("equipmentId") UUID equipmentId);
}
