package healoworld.slot_service.application.dto;

// ReleaseHold·ReleaseSlot 처리 결과
public enum ReleaseOutcome {
    RELEASED,        // 슬롯을 AVAILABLE로 반환함
    DETACHED,        // 장애로 UNAVAILABLE인 슬롯에서 예약 연결만 끊음 (상태 유지)
    NOT_APPLICABLE   // 이미 해제·만료됐거나 다른 예약의 슬롯이라 변경하지 않음
}
