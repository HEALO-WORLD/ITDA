package healoworld.slot_service.application.dto;

import healoworld.slot_service.domain.enums.HoldFailureReason;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class HoldResult {

    private final UUID slotId;
    private final UUID reservationId;
    private final boolean success;
    private final boolean alreadyHeld;   // 같은 예약으로 이미 HELD였음 (중복 요청)
    private final boolean notFound;      // 슬롯이 없음. 이벤트 실패 사유는 SLOT_CONFLICT, 메트릭에서만 구분
    private final HoldFailureReason failureReason;
    private final LocalDateTime heldUntil;

    public static HoldResult success(UUID slotId, UUID reservationId, LocalDateTime heldUntil) {
        return new HoldResult(slotId, reservationId, true, false, false, null, heldUntil);
    }

    public static HoldResult alreadyHeld(UUID slotId, UUID reservationId, LocalDateTime heldUntil) {
        return new HoldResult(slotId, reservationId, true, true, false, null, heldUntil);
    }

    public static HoldResult failed(UUID slotId, UUID reservationId, HoldFailureReason reason) {
        return new HoldResult(slotId, reservationId, false, false, false, reason, null);
    }

    // 없는 슬롯은 예외 대신 실패 결과로 돌려줘, 호출하는 쪽이 트랜잭션 롤백 없이 SlotHoldFailed를 기록하게 한다
    public static HoldResult notFound(UUID slotId, UUID reservationId) {
        return new HoldResult(slotId, reservationId, false, false, true, HoldFailureReason.SLOT_CONFLICT, null);
    }
}