package healoworld.referral_service.infrastructure.repository;

import healoworld.referral_service.domain.entity.Reservation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ReservationRepository extends JpaRepository<Reservation, UUID>, ReservationRepositoryCustom {
    Optional<Reservation> findByReferralId(UUID referralId);
    Optional<Reservation> findTopByReferralIdOrderByAttemptNoDesc(UUID referralId);
}
