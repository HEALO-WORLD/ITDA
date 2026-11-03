package healoworld.slot_service.application.dto;

// BookSlot 처리 결과
public enum BookOutcome {
    BOOKED,          // HELD → BOOKED 확정
    ALREADY_BOOKED,  // 같은 예약으로 이미 BOOKED (중복 요청)
    HOLD_EXPIRED,    // 같은 예약의 HOLD가 만료되어 확정하지 않고 만료 처리함
    FAILED           // 슬롯 없음, 해제됨, 다른 예약이 잡음, 삭제됨 등 확정 불가
}
