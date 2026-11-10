package healoworld.slot_service.global.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

// 슬롯 도메인 결과 카운터
// 트랜잭션 안에서 호출하면 커밋된 뒤에만 센다. 롤백되거나 재시도로 다시 처리돼도 결과가 두 번 세지지 않는다
@Component
@RequiredArgsConstructor
public class SlotMetrics {

    static final String HOLD = "slot.hold";
    static final String BOOK = "slot.book";
    static final String HOLD_EXPIRED = "slot.hold.expired";

    private final MeterRegistry meterRegistry;

    // HOLD 요청 결과
    public enum HoldOutcome {
        SUCCESS,      // AVAILABLE → HELD
        IDEMPOTENT,   // 같은 예약으로 이미 HELD (중복 요청)
        CONFLICT,     // 다른 예약이 잡았거나 BOOKED·UNAVAILABLE
        NOT_FOUND     // 슬롯 없음
    }

    // BookSlot 결과
    public enum BookResult {
        SUCCESS,       // HELD → BOOKED
        IDEMPOTENT,    // 같은 예약으로 이미 BOOKED (중복 요청)
        HOLD_EXPIRED,  // HOLD 만료 후 도착해 확정하지 않음
        FAILED         // 확정 불가 (다른 예약, 해제됨, 슬롯 없음 등)
    }

    public void hold(HoldOutcome outcome) {
        incrementAfterCommit(Counter.builder(HOLD)
                .description("HOLD 요청 처리 결과")
                .tag("result", tagValue(outcome))
                .register(meterRegistry));
    }

    public void book(BookResult result) {
        incrementAfterCommit(Counter.builder(BOOK)
                .description("BookSlot 처리 결과")
                .tag("result", tagValue(result))
                .register(meterRegistry));
    }

    // 스케줄러 만료와 만료 후 도착한 BookSlot 만료를 모두 센다 (후자는 slot.book{result=hold_expired}로도 보인다)
    public void holdExpired() {
        incrementAfterCommit(Counter.builder(HOLD_EXPIRED)
                .description("HOLD 만료 처리 건수")
                .register(meterRegistry));
    }

    private void incrementAfterCommit(Counter counter) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            counter.increment();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                counter.increment();
            }
        });
    }

    private static String tagValue(Enum<?> value) {
        return value.name().toLowerCase();
    }
}
