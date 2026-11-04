package healoworld.slot_service.application;

import healoworld.slot_service.application.service.SlotHoldService;
import healoworld.slot_service.infrastructure.repository.SlotRepository;
import healoworld.slot_service.infrastructure.repository.SlotStatusHistoryRepository;
import healoworld.slot_service.support.ConcurrentHoldRunner;
import healoworld.slot_service.support.ConcurrentHoldRunner.RoundResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.SoftAssertions.assertSoftly;

@SpringBootTest
@ActiveProfiles("test")
class SlotHoldConcurrencyTest {

    @Autowired
    SlotHoldService slotHoldService;
    @Autowired
    SlotRepository slotRepository;

    @Autowired
    SlotStatusHistoryRepository slotStatusHistoryRepository;

    @AfterEach
    void tearDown() {
        slotStatusHistoryRepository.deleteAllInBatch();
        slotRepository.deleteAllInBatch();   // DELETE FROM p_slots
    }

    @ParameterizedTest(name = "스레드 {0}개")
    @ValueSource(ints = {10, 50, 100})
    void 같은_슬롯에_동시_HOLD_요청시_하나만_성공한다(int threadCount) throws Exception {
        RoundResult result = ConcurrentHoldRunner.run(slotHoldService, slotRepository, threadCount);

        assertSoftly(softly -> {
            softly.assertThat(result.success()).as("성공").isEqualTo(1);
            softly.assertThat(result.fail()).as("거절").isEqualTo(threadCount - 1);
            softly.assertThat(result.error()).as("예외").isZero();
        });
    }
}