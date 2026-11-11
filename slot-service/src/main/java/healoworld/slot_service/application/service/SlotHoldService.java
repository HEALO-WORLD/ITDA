package healoworld.slot_service.application.service;

import healoworld.slot_service.application.dto.HoldResult;
import healoworld.slot_service.domain.entity.Slot;
import healoworld.slot_service.domain.enums.HoldFailureReason;
import healoworld.slot_service.domain.enums.SlotStatus;
import healoworld.slot_service.domain.enums.SlotStatusChangeReason;
import healoworld.slot_service.global.exception.BaseException;
import healoworld.slot_service.global.exception.ErrorCode;
import healoworld.slot_service.infrastructure.repository.SlotRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SlotHoldService {

    private final SlotRepository slotRepository;
    private final SlotStatusHistoryRecorder slotStatusHistoryRecorder;
    private final Clock clock;
    private final SlotDowntimeGuard downtimeGuard;

    // eventId: 이 HOLD를 요청한 HoldRequested 이벤트 ID. 상태 이력의 source_event_id로 남긴다
    @Transactional
    public HoldResult hold(UUID slotId, UUID reservationId, UUID eventId) {
        boolean blocked = downtimeGuard.isBlocked(slotId);
        LocalDateTime now = LocalDateTime.now(clock);
        if (blocked) {
            return HoldResult.failed(slotId, reservationId, HoldFailureReason.SLOT_CONFLICT);
        }
        LocalDateTime heldUntil = now.plus(Slot.HOLD_DURATION);

        int updated = slotRepository.holdIfAvailable(
                slotId, reservationId, heldUntil, now,
                SlotStatus.HELD, SlotStatus.AVAILABLE);

        if (updated == 1) {
            slotStatusHistoryRecorder.recordByEvent(slotId, reservationId,
                    SlotStatus.AVAILABLE, SlotStatus.HELD, SlotStatusChangeReason.HOLD, eventId, now);
            return HoldResult.success(slotId, reservationId, heldUntil);
        }

        // 아래는 상태가 바뀌지 않은 경우라 이력을 남기지 않는다

        // 0행: 없는 슬롯인지, 이미 내 예약으로 잡힌 건지, 남이 잡은 건지 구분
        Slot slot = slotRepository.findById(slotId)
                .orElseThrow(() -> new BaseException(ErrorCode.SLOT_NOT_FOUND));

        if (slot.isHeldBy(reservationId)) {
            return HoldResult.success(slotId, reservationId, slot.getHeldUntil());
        }
        return HoldResult.failed(slotId, reservationId, HoldFailureReason.SLOT_CONFLICT);
    }

}
