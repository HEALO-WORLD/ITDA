package healoworld.referral_service.global.deadletter;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface DeadLetterEventRepository extends JpaRepository<DeadLetterEvent, UUID> {
    boolean existsByOriginalTopicAndOriginalPartitionAndOriginalOffset(String topic, int partition, long offset);

    // 상태 필터 목록 (idx_dead_letter_status_created 인덱스 사용)
    Page<DeadLetterEvent> findByDeadLetterStatus(DeadLetterStatus deadLetterStatus, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from DeadLetterEvent d where d.id = :id")
    Optional<DeadLetterEvent> findByIdForUpdate(@Param("id") UUID id);
}
