package healoworld.slot_service.application.service;

import healoworld.slot_service.application.event.HoldRequestedEvent;
import org.springframework.stereotype.Service;

@Service
public class SlotCommandHandler {

    // TODO: 멱등성 확인 → HOLD → SlotHeld/SlotHoldFailed Outbox 기록 → 처리 완료 기록
    public void handleHoldRequested(HoldRequestedEvent event) {
        throw new UnsupportedOperationException("HoldRequested 처리는 아직 구현되지 않았습니다.");
    }
}
