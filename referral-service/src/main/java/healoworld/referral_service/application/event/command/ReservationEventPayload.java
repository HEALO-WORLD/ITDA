package healoworld.referral_service.application.event.command;

import healoworld.referral_service.domain.entity.Reservation;

import java.time.LocalDateTime;
import java.util.UUID;

// slot-commands로 보내는 커맨드 (Referral → Slot, key = slotId)
// 조건부 UPDATE로 바뀌지 않는 식별자만 담으므로, UPDATE 이후 detached 예약을 그대로 넘겨도 된다.
public record ReservationEventPayload(
        UUID reservationId,
        UUID referralId,
        UUID slotId,
        UUID eventId,
        String eventType,
        LocalDateTime occurredAt
) {
    public static ReservationEventPayload from(Reservation r, UUID eventId, String eventType) {
        return new ReservationEventPayload(
                r.getId(), r.getReferral().getId(), r.getSlotId(),
                eventId, eventType, LocalDateTime.now());
    }
}
