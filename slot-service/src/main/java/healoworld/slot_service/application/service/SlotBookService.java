package healoworld.slot_service.application.service;

import healoworld.slot_service.application.dto.BookOutcome;
import healoworld.slot_service.domain.entity.Slot;
import healoworld.slot_service.domain.enums.SlotStatus;
import healoworld.slot_service.domain.enums.SlotStatusChangeReason;
import healoworld.slot_service.infrastructure.repository.SlotRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.UUID;

// 슬롯 예약 확정(HELD → BOOKED). HOLD 만료 후 도착한 확정 요청은 확정하지 않는다
@Slf4j
@Service
@RequiredArgsConstructor
public class SlotBookService {

    private final SlotRepository slotRepository;
    private final SlotHoldExpiryService slotHoldExpiryService;
    private final SlotStatusHistoryRecorder slotStatusHistoryRecorder;
    private final Clock clock;

    // eventId: 이 확정을 요청한 BookSlot 이벤트 ID. 상태 이력의 source_event_id로 남긴다
    @Transactional
    public BookOutcome book(UUID slotId, UUID reservationId, UUID eventId) {
        LocalDateTime now = LocalDateTime.now(clock);

        int updated = slotRepository.bookIfHeld(slotId, reservationId, now, SlotStatus.HELD, SlotStatus.BOOKED);
        if (updated == 1) {
            slotStatusHistoryRecorder.recordByEvent(slotId, reservationId,
                    SlotStatus.HELD, SlotStatus.BOOKED, SlotStatusChangeReason.BOOK, eventId, now);
            return BookOutcome.BOOKED;
        }

        // 아래는 상태가 바뀌지 않은 경우라 이력을 남기지 않는다.
        // 단, 만료된 HOLD는 expire()가 HOLD_EXPIRED 이력을 남긴다

        // 0행: 없는 슬롯인지, 이미 확정된 건지, 만료된 건지, 남의 슬롯인지 구분
        Slot slot = slotRepository.findById(slotId).orElse(null);
        if (slot == null) {
            log.warn("BookSlot 슬롯 없음. slotId={}, reservationId={}", slotId, reservationId);
            return BookOutcome.FAILED;
        }
        if (slot.getDeletedAt() != null) {
            log.warn("BookSlot 삭제된 슬롯. slotId={}, reservationId={}", slotId, reservationId);
            return BookOutcome.FAILED;
        }
        if (slot.isBookedBy(reservationId)) {
            return BookOutcome.ALREADY_BOOKED;
        }
        if (slot.isHeldBy(reservationId) && slot.getHeldUntil().isAfter(now)) {
            // 만료 전인데 확정되지 않았다면 장비 중단 구간에 걸린 HOLD다 (bookIfHeld의 equipment_downtime_id 조건)
            // SlotBookFailed를 받은 Referral이 ReleaseHold로 보상하면 슬롯은 UNAVAILABLE로 반환된다
            log.warn("BookSlot 장비 중단으로 확정 거부. slotId={}, reservationId={}, downtimeId={}",
                    slotId, reservationId, slot.getEquipmentDowntimeId());
            return BookOutcome.FAILED;
        }
        if (slot.isHeldBy(reservationId)) {
            // 만료 시각이 지났지만 스케줄러가 아직 해제하지 않은 경우. 확정하지 않고 이 자리에서 만료 처리한다
            // expire()는 이 트랜잭션에 합류하므로 슬롯 해제, HoldExpired Outbox 기록이 함께 커밋된다
            slotHoldExpiryService.expire(slotId, reservationId, slot.getHeldUntil());
            return BookOutcome.HOLD_EXPIRED;
        }

        log.warn("BookSlot 확정 불가. slotId={}, reservationId={}, status={}, holder={}",
                slotId, reservationId, slot.getStatus(), slot.getReservationId());
        return BookOutcome.FAILED;
    }
}
