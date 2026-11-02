package healoworld.slot_service.global.inbox;

import healoworld.slot_service.support.FixedClockConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Import(FixedClockConfig.class)
class ConsumedEventRepositoryTest {

    private static final String CONSUMER = "slot-service";

    @Autowired
    private ConsumedEventRepository consumedEventRepository;

    @AfterEach
    void tearDown() {
        consumedEventRepository.deleteAllInBatch();
    }

    @Test
    void 처리한_이벤트는_eventId와_consumer로_조회된다() {
        UUID eventId = UUID.randomUUID();
        consumedEventRepository.saveAndFlush(
                ConsumedEvent.create(eventId, CONSUMER, "HoldRequested", FixedClockConfig.NOW));

        assertThat(consumedEventRepository.existsById(new ConsumedEventId(eventId, CONSUMER))).isTrue();
        assertThat(consumedEventRepository.existsById(new ConsumedEventId(eventId, "other-consumer"))).isFalse();
    }

    @Test
    void 같은_eventId와_consumer를_다시_저장하면_PK_충돌로_실패한다() {
        UUID eventId = UUID.randomUUID();
        consumedEventRepository.saveAndFlush(
                ConsumedEvent.create(eventId, CONSUMER, "HoldRequested", FixedClockConfig.NOW));

        assertThatThrownBy(() -> consumedEventRepository.saveAndFlush(
                ConsumedEvent.create(eventId, CONSUMER, "HoldRequested", FixedClockConfig.NOW)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
