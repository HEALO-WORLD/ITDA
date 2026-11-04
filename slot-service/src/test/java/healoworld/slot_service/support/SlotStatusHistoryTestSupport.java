package healoworld.slot_service.support;

import healoworld.slot_service.domain.entity.SlotStatusHistory;
import healoworld.slot_service.infrastructure.repository.SlotStatusHistoryRepository;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public final class SlotStatusHistoryTestSupport {

    private SlotStatusHistoryTestSupport() {
    }

    // 상태 이력이 정확히 한 건 남았는지 확인하고 그 이력을 반환한다
    public static SlotStatusHistory singleHistory(SlotStatusHistoryRepository repository) {
        List<SlotStatusHistory> histories = repository.findAll();
        assertThat(histories).as("슬롯 상태 이력은 한 건이어야 한다").hasSize(1);
        return histories.getFirst();
    }
}
