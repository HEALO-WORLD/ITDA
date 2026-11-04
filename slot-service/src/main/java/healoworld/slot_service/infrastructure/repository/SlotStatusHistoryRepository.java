package healoworld.slot_service.infrastructure.repository;

import healoworld.slot_service.domain.entity.SlotStatusHistory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface SlotStatusHistoryRepository extends JpaRepository<SlotStatusHistory, UUID> {

    // 슬롯 상태 이력 조회 API용. 오래된 변경부터 반환한다 (idx_slot_status_history_slot_changed_at 사용)
    Page<SlotStatusHistory> findBySlotIdOrderByChangedAtAsc(UUID slotId, Pageable pageable);
}
