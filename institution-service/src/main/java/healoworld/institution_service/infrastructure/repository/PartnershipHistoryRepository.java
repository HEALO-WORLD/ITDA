package healoworld.institution_service.infrastructure.repository;

import healoworld.institution_service.domain.entity.PartnershipHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface PartnershipHistoryRepository extends JpaRepository<PartnershipHistory, UUID> {
}
