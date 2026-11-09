package healoworld.referral_service.infrastructure.repository;

import healoworld.referral_service.domain.enums.ReservationStatus;

import java.util.UUID;

public record ReconciliationTarget(UUID reservationId, UUID slotId, ReservationStatus reservationStatus) {
}
