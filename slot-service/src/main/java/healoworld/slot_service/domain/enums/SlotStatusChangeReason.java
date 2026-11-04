package healoworld.slot_service.domain.enums;

// 슬롯 상태 이력(p_slot_status_histories)의 변경 사유
public enum SlotStatusChangeReason {
    HOLD,              // HoldRequested 수신: AVAILABLE → HELD
    BOOK,              // BookSlot 수신: HELD → BOOKED
    HOLD_EXPIRED,      // HOLD 만료 스케줄러: HELD → AVAILABLE
    RELEASE_HOLD,      // ReleaseHold 수신: HELD → AVAILABLE
    RELEASE_SLOT,      // ReleaseSlot 수신: BOOKED → AVAILABLE (UNAVAILABLE이면 예약 연결만 해제)
    ADMIN_RELEASE,     // ADMIN 강제 해제: HELD/BOOKED → AVAILABLE
    DOWNTIME_STARTED,  // 장비 중단 등록: → UNAVAILABLE
    DOWNTIME_ENDED     // 장비 중단 종료·취소: UNAVAILABLE → AVAILABLE
}
