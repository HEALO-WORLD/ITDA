package healoworld.slot_service.application.event.command;

import java.time.LocalDateTime;
import java.util.UUID;

// slot-commands로 수신하는 임시 선점 해제 요청 (Referral → Slot, key = slotId)
// 확정 실패 시 보상 처리로 발행된다. Referral은 예약을 FAILED(CONFIRM_FAILED)로 바꾼 뒤 보낸다
public record ReleaseHoldEvent(
        UUID eventId,
        String eventType,
        LocalDateTime occurredAt,
        UUID slotId,
        UUID reservationId
) implements SlotCommand {
    public static final String TYPE = "ReleaseHold";
}
