package healoworld.referral_service.domain.policy;

import healoworld.referral_service.application.event.result.SlotResultType;
import healoworld.referral_service.domain.enums.ReservationStatus;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;


public class ReservationReconciliationPolicy {
    public Optional<SlotResultType> decide(UUID reservationId, ReservationStatus reservationStatus, SlotSnapshot slotSnapshot, LocalDateTime now) {
        switch (reservationStatus) {
            case REQUESTED -> {
                if (slotSnapshot.isUnavailable()) {
                    return Optional.of(SlotResultType.SLOTS_BLOCKED);
                }
                if (!slotSnapshot.isOwnedBy(reservationId)) {
                    return Optional.of(SlotResultType.SLOT_HOLD_FAILED);
                }
                return Optional.of(SlotResultType.SLOT_HELD);
            }

            case HELD -> {
                if (slotSnapshot.isUnavailable()) {
                    return Optional.of(SlotResultType.SLOTS_BLOCKED);
                }
                if (!slotSnapshot.isOwnedBy(reservationId)) {
                    return Optional.of(SlotResultType.HOLD_EXPIRED);
                }
                if (slotSnapshot.isBooked()) {
                    return Optional.of(SlotResultType.SLOT_BOOKED);
                }
            }

            case CONFIRMED -> {
                if (!(slotSnapshot.isOwnedBy(reservationId) && slotSnapshot.isBooked())) {
                    return Optional.of(SlotResultType.SLOTS_BLOCKED);
                }
            }

            case FAILED, BLOCKED, CANCELED -> {
                if (slotSnapshot.isBooked() && slotSnapshot.isOwnedBy(reservationId)) {
                    return Optional.of(SlotResultType.SLOT_BOOKED);
                }
            }
        }

        return Optional.empty();
    }

}
