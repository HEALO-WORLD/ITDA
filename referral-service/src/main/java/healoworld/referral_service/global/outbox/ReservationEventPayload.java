package healoworld.referral_service.global.outbox;

import healoworld.referral_service.domain.entity.Reservation;
import healoworld.referral_service.domain.enums.ReservationStatus;

import java.time.LocalDateTime;
import java.util.UUID;

public record ReservationEventPayload(
        UUID reservationId,
        UUID referralId,
        Integer attemptNo,
        UUID slotId,
        UUID examInstitutionId,
        UUID equipmentId,
        LocalDateTime examDate,
        LocalDateTime startTime,
        LocalDateTime endTime,
        ReservationStatus reservationStatus,
        LocalDateTime occurredAt
) {
    public static ReservationEventPayload from(Reservation r) {
        return new ReservationEventPayload(
                r.getId(), r.getReferral().getId(), r.getAttemptNo(), r.getSlotId(),
                r.getExamInstitutionId(), r.getEquipmentId(),
                r.getExamDate(), r.getStartTime(), r.getEndTime(), r.getReservationStatus(), LocalDateTime.now());
    }
}
