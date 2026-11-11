package healoworld.referral_service.infrastructure.client.slot;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;
import java.util.UUID;

@FeignClient(name = "slot-service")
public interface SlotClient {
    @PostMapping("/api/v1/internal/slots/statuses")
    List<SlotStatusResponse> getSlotStatuses(@RequestBody SlotStatusQueryRequest request);

    @GetMapping("/api/v1/internal/slots/{slotId}")
    SlotReservationContextResponse getReservationContext(@PathVariable("slotId") UUID slotId);
}
