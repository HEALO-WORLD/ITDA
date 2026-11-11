package healoworld.slot_service.infrastructure.repository;

import java.time.LocalDateTime;
import java.util.UUID;

// HOLD 만료 처리에 필요한 슬롯 정보만 담은 조회 결과
public record ExpiredHold(
        UUID slotId,
        UUID reservationId,
        LocalDateTime heldUntil
) {
}
