package healoworld.slot_service.application.event.result;

import healoworld.slot_service.application.event.command.HoldRequestedEvent;
import healoworld.slot_service.domain.enums.HoldFailureReason;

import java.time.LocalDateTime;
import java.util.UUID;

// slot-results로 발행하는 HOLD 실패 결과 (Slot → Referral, key = slotId)
// slot-results 이벤트는 referralId를 담지 않는다. Referral은 reservationId로 예약·의뢰를 조회한다
public record SlotHoldFailedEvent(
        UUID eventId,
        String eventType,
        LocalDateTime occurredAt,
        UUID slotId,
        UUID reservationId,
        HoldFailureReason failureReason
) {
    public static final String TYPE = "SlotHoldFailed";

    public static SlotHoldFailedEvent of(HoldRequestedEvent request, HoldFailureReason reason, LocalDateTime now) {
        return new SlotHoldFailedEvent(UUID.randomUUID(), TYPE, now,
                request.slotId(), request.reservationId(), reason);
    }
}
