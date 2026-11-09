package healoworld.slot_service.application.service;

import healoworld.slot_service.domain.entity.Slot;
import healoworld.slot_service.domain.entity.SlotStatusHistory;
import healoworld.slot_service.domain.enums.SlotStatus;
import healoworld.slot_service.domain.enums.SlotStatusChangeReason;
import healoworld.slot_service.infrastructure.repository.SlotRepository;
import healoworld.slot_service.infrastructure.repository.SlotStatusHistoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

// 슬롯 상태 이력 기록. 상태 변경(벌크 UPDATE)이 실제로 반영된 경우에만 호출한다.
// 이력과 상태 변경이 함께 커밋·롤백되도록 호출한 쪽의 트랜잭션 안에서만 실행한다 (MANDATORY)
//
// changedAt에는 벌크 UPDATE에서 updated_at으로 쓴 now를 그대로 넘긴다.
// 여기서 시각을 따로 구하면 슬롯의 updated_at과 이력의 changed_at이 어긋난다
@Component
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class SlotStatusHistoryRecorder {

    private final SlotRepository slotRepository;
    private final SlotStatusHistoryRepository slotStatusHistoryRepository;

    // Kafka 이벤트(HoldRequested, BookSlot, ReleaseHold, ReleaseSlot)로 상태가 바뀐 경우
    public void recordByEvent(UUID slotId, UUID reservationId,
                              SlotStatus fromStatus, SlotStatus toStatus,
                              SlotStatusChangeReason reason, UUID sourceEventId,
                              LocalDateTime changedAt) {
        slotStatusHistoryRepository.save(SlotStatusHistory.ofEvent(
                slotRef(slotId), reservationId, fromStatus, toStatus, reason, sourceEventId, changedAt));
    }

    // API 요청(ADMIN 강제 해제 등)으로 상태가 바뀐 경우
    public void recordByUser(UUID slotId, UUID reservationId,
                             SlotStatus fromStatus, SlotStatus toStatus,
                             SlotStatusChangeReason reason, UUID changedBy,
                             LocalDateTime changedAt) {
        slotStatusHistoryRepository.save(SlotStatusHistory.ofUser(
                slotRef(slotId), reservationId, fromStatus, toStatus, reason, changedAt, changedBy));
    }

    // 스케줄러(HOLD 만료)로 상태가 바뀐 경우
    public void recordBySystem(UUID slotId, UUID reservationId,
                               SlotStatus fromStatus, SlotStatus toStatus,
                               SlotStatusChangeReason reason,
                               LocalDateTime changedAt) {
        slotStatusHistoryRepository.save(SlotStatusHistory.ofSystem(
                slotRef(slotId), reservationId, fromStatus, toStatus, reason, changedAt));
    }

    // 이력에는 slot_id만 필요하므로 SELECT 없이 프록시로 연결한다.
    // 호출 시점에는 벌크 UPDATE가 1건 반영되어 슬롯이 존재함이 확인된 상태다
    private Slot slotRef(UUID slotId) {
        return slotRepository.getReferenceById(slotId);
    }
}
