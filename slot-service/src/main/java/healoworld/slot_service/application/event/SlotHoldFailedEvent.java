package healoworld.slot_service.application.event;

import healoworld.slot_service.domain.enums.HoldFailureReason;

import java.time.LocalDateTime;
import java.util.UUID;

// slot-results로 발행하는 HOLD 실패 결과 (Slot → Referral, key = slotId)
public record SlotHoldFailedEvent(
        UUID eventId,
        String eventType,
        LocalDateTime occurredAt,
        UUID slotId,
        UUID reservationId,
        UUID referralId,
        HoldFailureReason failureReason
) {
    public static final String TYPE = "SlotHoldFailed";

    public static SlotHoldFailedEvent of(HoldRequestedEvent request, HoldFailureReason reason, LocalDateTime now) {
        return new SlotHoldFailedEvent(UUID.randomUUID(), TYPE, now,
                request.slotId(), request.reservationId(), request.referralId(), reason);
    }
}
