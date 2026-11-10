package healoworld.referral_service.infrastructure.client.slot;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;

@FeignClient(name = "slot-service")
public interface SlotClient {
    @PostMapping("/api/v1/internal/slots/statuses")
    List<SlotStatusResponse> getSlotStatuses(@RequestBody SlotStatusQueryRequest request);
}
