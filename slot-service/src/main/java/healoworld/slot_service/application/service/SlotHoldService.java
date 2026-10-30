package healoworld.slot_service.application.service;

import healoworld.slot_service.application.dto.HoldResult;
import healoworld.slot_service.domain.entity.Slot;
import healoworld.slot_service.domain.enums.HoldFailureReason;
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
    private final Clock clock;

    @Transactional
    public HoldResult hold(UUID slotId, UUID reservationId) {
        Slot slot = slotRepository.findById(slotId)
                .orElseThrow(() -> new BaseException(ErrorCode.SLOT_NOT_FOUND));

        if (!slot.isHoldable()) {
            return HoldResult.failed(slotId, reservationId, HoldFailureReason.SLOT_CONFLICT);
        }

        slot.hold(reservationId, LocalDateTime.now(clock));
        return HoldResult.success(slotId, reservationId, slot.getHeldUntil());
    }
}