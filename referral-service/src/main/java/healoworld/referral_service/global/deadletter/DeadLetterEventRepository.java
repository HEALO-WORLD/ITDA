package healoworld.referral_service.global.deadletter;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface DeadLetterEventRepository extends JpaRepository<DeadLetterEvent, UUID> {
    boolean existsByOriginalTopicAndOriginalPartitionAndOriginalOffset(String topic, int partition, long offset);
}
