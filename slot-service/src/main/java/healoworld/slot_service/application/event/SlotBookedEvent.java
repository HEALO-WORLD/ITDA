package healoworld.slot_service.application.event;

import java.time.LocalDateTime;
import java.util.UUID;

// slot-results로 발행하는 예약 확정 성공 결과 (Slot → Referral, key = slotId)
// slot-results 이벤트는 referralId를 담지 않는다. Referral은 reservationId로 예약·의뢰를 조회한다
public record SlotBookedEvent(
        UUID eventId,
        String eventType,
        LocalDateTime occurredAt,
        UUID slotId,
        UUID reservationId
) {
    public static final String TYPE = "SlotBooked";

    public static SlotBookedEvent of(BookSlotEvent request, LocalDateTime now) {
        return new SlotBookedEvent(UUID.randomUUID(), TYPE, now, request.slotId(), request.reservationId());
    }
}
