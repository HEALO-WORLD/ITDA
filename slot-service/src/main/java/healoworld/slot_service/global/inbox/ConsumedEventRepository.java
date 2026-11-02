package healoworld.slot_service.global.inbox;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ConsumedEventRepository extends JpaRepository<ConsumedEvent, ConsumedEventId> {
}
