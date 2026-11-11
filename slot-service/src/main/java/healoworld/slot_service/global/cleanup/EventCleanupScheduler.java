package healoworld.slot_service.global.cleanup;

import healoworld.slot_service.global.inbox.ConsumedEventRepository;
import healoworld.slot_service.global.outbox.OutboxEventRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.function.ToIntFunction;

// 발행 완료된 Outbox 이벤트와 오래된 소비 기록을 주기적으로 지운다.
// 소비 기록은 Kafka가 같은 메시지를 다시 보낼 수 있는 기간(토픽 보관·오프셋 보관 기본 7일)보다 길게 남겨야
// 재전달된 메시지를 다시 처리하지 않는다
@Slf4j
@Component
@ConditionalOnProperty(name = "event-cleanup.enabled", havingValue = "true", matchIfMissing = true)
public class EventCleanupScheduler {

    private final OutboxEventRepository outboxEventRepository;
    private final ConsumedEventRepository consumedEventRepository;
    private final Clock clock;
    private final int outboxRetentionDays;
    private final int consumedRetentionDays;
    private final int batchSize;

    public EventCleanupScheduler(
            OutboxEventRepository outboxEventRepository,
            ConsumedEventRepository consumedEventRepository,
            Clock clock,
            @Value("${event-cleanup.outbox-retention-days:7}") int outboxRetentionDays,
            @Value("${event-cleanup.consumed-retention-days:14}") int consumedRetentionDays,
            @Value("${event-cleanup.batch-size:1000}") int batchSize
    ) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("event-cleanup.batch-size는 1 이상이어야 합니다.");
        }
        this.outboxEventRepository = outboxEventRepository;
        this.consumedEventRepository = consumedEventRepository;
        this.clock = clock;
        this.outboxRetentionDays = outboxRetentionDays;
        this.consumedRetentionDays = consumedRetentionDays;
        this.batchSize = batchSize;
    }

    // 기본 매일 새벽 4시(한국 시간). 트래픽이 적은 시간에 실행한다
    @Scheduled(cron = "${event-cleanup.cron:0 0 4 * * *}", zone = "Asia/Seoul")
    public void cleanUp() {
        LocalDateTime now = LocalDateTime.now(clock);

        int outbox = deleteInBatches(
                cutoff -> outboxEventRepository.deletePublishedBefore(cutoff, batchSize),
                now.minusDays(outboxRetentionDays));
        int consumed = deleteInBatches(
                cutoff -> consumedEventRepository.deleteProcessedBefore(cutoff, batchSize),
                now.minusDays(consumedRetentionDays));

        log.info("이벤트 테이블 정리 완료. outbox={}, consumed={}", outbox, consumed);
    }

    // 한 번에 batchSize건씩 지우고, batchSize보다 적게 지워지면 남은 대상이 없으므로 멈춘다.
    // 삭제는 배치마다 별도 트랜잭션이라 테이블을 오래 잠그지 않는다
    private int deleteInBatches(ToIntFunction<LocalDateTime> delete, LocalDateTime cutoff) {
        int total = 0;
        int deleted;
        do {
            deleted = delete.applyAsInt(cutoff);
            total += deleted;
        } while (deleted == batchSize);
        return total;
    }
}
