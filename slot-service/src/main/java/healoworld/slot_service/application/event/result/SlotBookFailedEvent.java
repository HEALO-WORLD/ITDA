package healoworld.slot_service.application.event.result;

import healoworld.slot_service.application.event.command.BookSlotEvent;

import java.time.LocalDateTime;
import java.util.UUID;

// slot-results로 발행하는 예약 확정 실패 결과 (Slot → Referral, key = slotId)
// Referral은 원인과 관계없이 CONFIRM_FAILED로 보상 처리하므로 실패 사유는 담지 않는다. 원인은 Slot 로그로 확인한다
// slot-results 이벤트는 referralId를 담지 않는다. Referral은 reservationId로 예약·의뢰를 조회한다
public record SlotBookFailedEvent(
        UUID eventId,
        String eventType,
        LocalDateTime occurredAt,
        UUID slotId,
        UUID reservationId
) {
    public static final String TYPE = "SlotBookFailed";

    public static SlotBookFailedEvent of(BookSlotEvent request, LocalDateTime now) {
        return new SlotBookFailedEvent(UUID.randomUUID(), TYPE, now, request.slotId(), request.reservationId());
    }
}
