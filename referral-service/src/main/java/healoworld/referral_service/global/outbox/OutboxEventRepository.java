package healoworld.referral_service.global.outbox;

import jakarta.transaction.Transactional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {
    // 미발행 이벤트를 생성 순서대로 최대 100건
    List<OutboxEvent> findTop100ByPublishedAtIsNullOrderByCreatedAtAsc();

    @Modifying(clearAutomatically = true)
    @Transactional
    @Query("update OutboxEvent e set e.publishedAt = :now, e.updatedAt = :now where e.id = :id")
    int markPublished(@Param("id") UUID id, @Param("now") LocalDateTime now);

    @Modifying(clearAutomatically = true)
    @Transactional
    @Query("update OutboxEvent e set e.retryCount = e.retryCount + 1, e.updatedAt = :now where e.id = :id")
    int increaseRetryCount(@Param("id") UUID id, @Param("now") LocalDateTime now);
}
