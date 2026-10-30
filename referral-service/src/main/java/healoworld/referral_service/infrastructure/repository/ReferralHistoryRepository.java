package healoworld.referral_service.infrastructure.repository;

import healoworld.referral_service.domain.entity.ReferralHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ReferralHistoryRepository extends JpaRepository<ReferralHistory, UUID> {
    List<ReferralHistory> findAllByReferralIdOrderByOccurredAtAsc(UUID referralId);
}
