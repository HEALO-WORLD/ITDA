package healoworld.referral_service.infrastructure.client.slot;

import healoworld.referral_service.domain.policy.SlotSnapshot;

import java.time.LocalDateTime;
import java.util.UUID;

public record SlotStatusResponse(
        UUID slotId,
        String status,
        UUID reservationId,
        LocalDateTime heldUntil
) {
    public SlotSnapshot toSnapshot() {
        return new SlotSnapshot(this.status(), this.reservationId(), this.heldUntil());
    }
}
