package healoworld.referral_service.infrastructure.repository;

import healoworld.referral_service.domain.entity.Reservation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface ReservationRepository extends JpaRepository<Reservation, UUID>, ReservationRepositoryCustom {
    Optional<Reservation> findTopByReferralIdOrderByAttemptNoDesc(UUID referralId);

    Optional<Reservation> findByReferralIdAndAttemptNo(UUID id, int i);

    @Query("select r.referral.id from Reservation r where r.id = :id")
    Optional<UUID> findReferralIdById(@Param("id") UUID id);
}
