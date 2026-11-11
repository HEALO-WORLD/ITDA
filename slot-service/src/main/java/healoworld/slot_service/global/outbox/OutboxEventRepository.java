package healoworld.slot_service.global.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

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

    // 발행 완료 후 cutoff 이전인 이벤트를 최대 batchSize건 지운다. 미발행(published_at IS NULL)은 조건에 걸리지 않는다
    // PostgreSQL은 DELETE ... LIMIT가 없어 서브쿼리로 건수를 제한한다
    @Modifying
    @Transactional
    @Query(value = """
        delete from p_outbox_events
         where id in (select id
                        from p_outbox_events
                       where published_at < :cutoff
                       limit :batchSize)
        """, nativeQuery = true)
    int deletePublishedBefore(@Param("cutoff") LocalDateTime cutoff, @Param("batchSize") int batchSize);
}