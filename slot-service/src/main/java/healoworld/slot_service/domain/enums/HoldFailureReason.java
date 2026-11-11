package healoworld.slot_service.domain.enums;

public enum HoldFailureReason {
    SLOT_CONFLICT,   // 다른 예약이 잡았거나 BOOKED·UNAVAILABLE이거나, 삭제됐거나 없음
    SLOT_EXPIRED     // 슬롯 시작 시각이 이미 지남
}
