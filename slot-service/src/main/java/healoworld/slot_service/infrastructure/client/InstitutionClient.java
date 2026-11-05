package healoworld.slot_service.infrastructure.client;

import healoworld.slot_service.global.dto.ApiResponse;
import healoworld.slot_service.infrastructure.client.dto.EquipmentSlotContextResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

@FeignClient(name = "institution-service")
public interface InstitutionClient {

    @GetMapping("/api/v1/internal/equipments/{equipmentId}/slot-context")
    ApiResponse<EquipmentSlotContextResponse> getSlotContext(
            @PathVariable("equipmentId") UUID equipmentId,
            @RequestParam("examTypeId") UUID examTypeId,
            @RequestParam("date")
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date
    );

    @GetMapping("/api/v1/internal/equipments/visible")
    ApiResponse<Set<UUID>> getVisibleEquipmentIds(
            @RequestParam("callerInstitutionId") UUID callerInstitutionId,
            @RequestParam("role") String role,
            @RequestParam(name = "institutionId", required = false)
            UUID institutionId,
            @RequestParam("onlyOperational") boolean onlyOperational
    );
}