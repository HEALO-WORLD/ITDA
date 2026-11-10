package healoworld.referral_service.domain.policy;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

public record SlotSnapshot(String status, UUID reservationId, LocalDateTime heldUntil) {
    public boolean isOwnedBy(UUID reservationId) {
        return reservationId.equals(this.reservationId) && (isHeld() || isBooked());
    }

    public boolean isHeld() {
        return Objects.equals(this.status, "HELD");
    }

    public boolean isBooked() {
        return Objects.equals(this.status, "BOOKED");
    }

    public boolean isUnavailable() {
        return Objects.equals(this.status, "UNAVAILABLE");
    }
}
