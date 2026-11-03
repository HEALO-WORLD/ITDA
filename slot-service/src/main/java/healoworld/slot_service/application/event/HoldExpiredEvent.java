package healoworld.slot_service.application.event;

import java.time.LocalDateTime;
import java.util.UUID;

// slot-results로 발행하는 HOLD 만료 결과 (Slot → Referral, key = slotId)
// Slot은 referralId를 보관하지 않으므로 reservationId만 담는다. Referral은 reservationId로 예약·의뢰를 조회한다
public record HoldExpiredEvent(
        UUID eventId,
        String eventType,
        LocalDateTime occurredAt,
        UUID slotId,
        UUID reservationId,
        LocalDateTime heldUntil
) {
    public static final String TYPE = "HoldExpired";

    public static HoldExpiredEvent of(UUID slotId, UUID reservationId, LocalDateTime heldUntil, LocalDateTime now) {
        return new HoldExpiredEvent(UUID.randomUUID(), TYPE, now, slotId, reservationId, heldUntil);
    }
}
