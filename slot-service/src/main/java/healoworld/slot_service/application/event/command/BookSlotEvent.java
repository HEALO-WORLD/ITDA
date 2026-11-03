package healoworld.slot_service.application.event.command;

import java.time.LocalDateTime;
import java.util.UUID;

// slot-commands로 수신하는 예약 확정 요청 (Referral → Slot, key = slotId)
public record BookSlotEvent(
        UUID eventId,
        String eventType,
        LocalDateTime occurredAt,
        UUID slotId,
        UUID reservationId
) implements SlotCommand {
    public static final String TYPE = "BookSlot";
}
