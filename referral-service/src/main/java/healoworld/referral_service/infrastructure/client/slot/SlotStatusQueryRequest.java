package healoworld.referral_service.infrastructure.client.slot;

import java.util.List;
import java.util.UUID;

public record SlotStatusQueryRequest(List<UUID> slotIds) {}

