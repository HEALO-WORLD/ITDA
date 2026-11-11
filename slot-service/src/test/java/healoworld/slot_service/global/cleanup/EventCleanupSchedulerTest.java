package healoworld.slot_service.global.cleanup;

import healoworld.slot_service.global.inbox.ConsumedEvent;
import healoworld.slot_service.global.inbox.ConsumedEventId;
import healoworld.slot_service.global.inbox.ConsumedEventRepository;
import healoworld.slot_service.global.outbox.OutboxEvent;
import healoworld.slot_service.global.outbox.OutboxEventRepository;
import healoworld.slot_service.support.FixedClockConfig;
import healoworld.slot_service.support.PostgresContainerSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// 보관 기간: Outbox 7일, 소비 기록 14일. 배치 크기는 나눠 지우기를 확인하려고 2로 둔다
@SpringBootTest(properties = {
        "event-cleanup.enabled=true",
        "event-cleanup.outbox-retention-days=7",
        "event-cleanup.consumed-retention-days=14",
        "event-cleanup.batch-size=2"
})
@ActiveProfiles("test")
@Import(FixedClockConfig.class)
class EventCleanupSchedulerTest extends PostgresContainerSupport {

    private static final LocalDateTime NOW = FixedClockConfig.NOW;
    private static final String CONSUMER = "slot-service";

    @Autowired
    private EventCleanupScheduler eventCleanupScheduler;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private ConsumedEventRepository consumedEventRepository;

    @AfterEach
    void tearDown() {
        outboxEventRepository.deleteAllInBatch();
        consumedEventRepository.deleteAllInBatch();
    }

    // ---------- Outbox ----------

    @Test
    void 발행_완료_후_보관_기간이_지난_Outbox는_지운다() {
        UUID old = savePublishedOutbox(NOW.minusDays(8));

        eventCleanupScheduler.cleanUp();

        assertThat(outboxEventRepository.existsById(old)).isFalse();
    }

    @Test
    void 발행_완료_후_보관_기간이_지나지_않은_Outbox는_남긴다() {
        UUID recent = savePublishedOutbox(NOW.minusDays(6));

        eventCleanupScheduler.cleanUp();

        assertThat(outboxEventRepository.existsById(recent)).isTrue();
    }

    @Test
    void 미발행_Outbox는_오래됐어도_지우지_않는다() {
        UUID unpublished = saveOutbox(NOW.minusDays(30));

        eventCleanupScheduler.cleanUp();

        assertThat(outboxEventRepository.existsById(unpublished)).isTrue();
    }

    @Test
    void 대상이_배치_크기보다_많으면_나눠서_모두_지운다() {
        for (int i = 0; i < 5; i++) {
            savePublishedOutbox(NOW.minusDays(8));
        }
        UUID recent = savePublishedOutbox(NOW.minusDays(1));

        eventCleanupScheduler.cleanUp();

        assertThat(outboxEventRepository.findAll())
                .extracting(OutboxEvent::getId)
                .containsExactly(recent);
    }

    // ---------- 소비 기록 ----------

    @Test
    void 보관_기간이_지난_소비_기록은_지운다() {
        UUID old = saveConsumed(NOW.minusDays(15));

        eventCleanupScheduler.cleanUp();

        assertThat(consumedEventRepository.existsById(new ConsumedEventId(old, CONSUMER))).isFalse();
    }

    @Test
    void 보관_기간이_지나지_않은_소비_기록은_남긴다() {
        UUID recent = saveConsumed(NOW.minusDays(13));

        eventCleanupScheduler.cleanUp();

        assertThat(consumedEventRepository.existsById(new ConsumedEventId(recent, CONSUMER))).isTrue();
    }

    @Test
    void 소비_기록도_배치_크기보다_많으면_나눠서_모두_지운다() {
        for (int i = 0; i < 5; i++) {
            saveConsumed(NOW.minusDays(15));
        }
        UUID recent = saveConsumed(NOW.minusDays(1));

        eventCleanupScheduler.cleanUp();

        // ConsumedEvent.getId()는 Persistable 구현이라 복합키(ConsumedEventId)를 돌려준다
        assertThat(consumedEventRepository.findAll())
                .extracting(ConsumedEvent::getId)
                .containsExactly(new ConsumedEventId(recent, CONSUMER));
    }

    // ---------- 준비 ----------

    private UUID saveOutbox(LocalDateTime createdAt) {
        UUID id = UUID.randomUUID();
        outboxEventRepository.saveAndFlush(OutboxEvent.create(id, "SLOT", UUID.randomUUID(), "slot-results",
                UUID.randomUUID().toString(), "SlotHeld", "{}", createdAt));
        return id;
    }

    private UUID savePublishedOutbox(LocalDateTime publishedAt) {
        UUID id = saveOutbox(publishedAt);
        outboxEventRepository.markPublished(id, publishedAt);
        return id;
    }

    private UUID saveConsumed(LocalDateTime processedAt) {
        UUID id = UUID.randomUUID();
        consumedEventRepository.saveAndFlush(ConsumedEvent.create(id, CONSUMER, "HoldRequested", processedAt));
        return id;
    }
}
