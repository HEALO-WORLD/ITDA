package healoworld.slot_service.application.event.command;

import java.time.LocalDateTime;
import java.util.UUID;

// slot-commands로 수신하는 확정 예약 슬롯 해제 요청 (Referral → Slot, key = slotId)
// 의뢰기관이 예약을 취소하면 Referral이 의뢰를 CANCELED로 바꾼 뒤 보낸다
public record ReleaseSlotEvent(
        UUID eventId,
        String eventType,
        LocalDateTime occurredAt,
        UUID slotId,
        UUID reservationId
) implements SlotCommand {
    public static final String TYPE = "ReleaseSlot";
}
