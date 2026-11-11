package healoworld.slot_service.global.inbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

public interface ConsumedEventRepository extends JpaRepository<ConsumedEvent, ConsumedEventId> {

    // cutoff 이전에 처리한 소비 기록을 최대 batchSize건 지운다
    // PK가 (id, consumer) 복합키라 행 위치(ctid)로 대상을 지정한다
    @Modifying
    @Transactional
    @Query(value = """
        delete from p_consumed_events
         where ctid in (select ctid
                          from p_consumed_events
                         where processed_at < :cutoff
                         limit :batchSize)
        """, nativeQuery = true)
    int deleteProcessedBefore(@Param("cutoff") LocalDateTime cutoff, @Param("batchSize") int batchSize);
}
