package healoworld.slot_service.global.metrics;

import healoworld.slot_service.global.metrics.SlotMetrics.HoldOutcome;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThat;

class SlotMetricsTest {

    private SimpleMeterRegistry meterRegistry;
    private SlotMetrics slotMetrics;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        slotMetrics = new SlotMetrics(meterRegistry);
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void 트랜잭션_밖이면_바로_센다() {
        slotMetrics.hold(HoldOutcome.SUCCESS);

        assertThat(holdCount("success")).isEqualTo(1);
    }

    @Test
    void 트랜잭션_안이면_커밋된_뒤에_센다() {
        TransactionSynchronizationManager.initSynchronization();

        slotMetrics.hold(HoldOutcome.CONFLICT);
        assertThat(holdCount("conflict")).isZero();

        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        assertThat(holdCount("conflict")).isEqualTo(1);
    }

    @Test
    void 롤백되면_세지_않는다() {
        TransactionSynchronizationManager.initSynchronization();

        slotMetrics.holdExpired();
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

        Counter counter = meterRegistry.find(SlotMetrics.HOLD_EXPIRED).counter();
        assertThat(counter == null ? 0 : counter.count()).isZero();
    }

    private double holdCount(String result) {
        Counter counter = meterRegistry.find(SlotMetrics.HOLD).tag("result", result).counter();
        return counter == null ? 0 : counter.count();
    }
}
