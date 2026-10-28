package healoworld.referral_service.infrastructure.repository;

import healoworld.referral_service.domain.entity.Referral;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ReferralRepository extends JpaRepository<Referral, UUID> {
}
