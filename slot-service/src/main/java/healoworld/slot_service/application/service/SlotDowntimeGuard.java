package healoworld.slot_service.application.service;

import healoworld.slot_service.domain.entity.Slot;
import healoworld.slot_service.infrastructure.repository.EquipmentDowntimeRepository;
import healoworld.slot_service.infrastructure.repository.SlotCreationLockRepository;
import healoworld.slot_service.infrastructure.repository.SlotRepository;
import java.time.LocalDateTime;
import java.time.Clock;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SlotDowntimeGuard {
    private final SlotRepository slots;
    private final EquipmentDowntimeRepository downtimes;
    private final SlotCreationLockRepository locks;
    private final Clock clock;

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean isBlocked(UUID slotId) {
        UUID equipmentId = slots.findEquipmentIdBySlotId(slotId).orElse(null);
        if (equipmentId == null) {
            return false; // 없는 슬롯의 기존 오류 처리는 호출자가 유지한다.
        }
        // 잠금 대기 전에 Slot 엔티티를 영속성 컨텍스트에 올리지 않는다.
        // 잠금 이후에 읽어야 먼저 커밋된 중단 표시·예약 상태를 볼 수 있다.
        locks.lockEquipmentShared(equipmentId);
        Slot slot = slots.findById(slotId).orElse(null);
        if (slot == null) {
            return false; // 잠금 대기 중 삭제된 경우도 호출자의 기존 오류 처리를 유지한다.
        }
        return downtimes.existsBlockingOverlap(slot.getEquipmentId(),
                slot.getDate().atTime(slot.getStartTime()), slot.getDate().atTime(slot.getEndTime()), LocalDateTime.now(clock));
    }
}
