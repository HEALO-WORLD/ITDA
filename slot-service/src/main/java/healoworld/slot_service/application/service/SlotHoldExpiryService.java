package healoworld.slot_service.application.service;

import healoworld.slot_service.application.event.result.HoldExpiredEvent;
import healoworld.slot_service.domain.enums.SlotStatus;
import healoworld.slot_service.domain.enums.SlotStatusChangeReason;
import healoworld.slot_service.global.outbox.OutboxEvent;
import healoworld.slot_service.global.outbox.OutboxEventRepository;
import healoworld.slot_service.infrastructure.repository.SlotRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.UUID;

// HOLD 만료 처리. 슬롯 해제와 HoldExpired Outbox 기록을 슬롯 한 건 단위 트랜잭션에서 수행한다
@Slf4j
@Service
@RequiredArgsConstructor
public class SlotHoldExpiryService {

    private static final String AGGREGATE_TYPE = "SLOT";
    private static final String RESULT_TOPIC = "slot-results";

    private final SlotRepository slotRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final SlotStatusHistoryRecorder slotStatusHistoryRecorder;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    // 만료 처리했으면 true, 그 사이 확정·해제·재HOLD되어 대상이 아니면 false
    @Transactional
    public boolean expire(UUID slotId, UUID reservationId, LocalDateTime heldUntil) {
        LocalDateTime now = LocalDateTime.now(clock);

        // 장비 중단 구간에 걸린 HOLD는 AVAILABLE이 아닌 UNAVAILABLE로 만료시킨다
        SlotStatus toStatus = SlotStatus.AVAILABLE;
        int updated = slotRepository.expireHoldIfDue(
                slotId, reservationId, now, SlotStatus.HELD, SlotStatus.AVAILABLE);
        if (updated == 0) {
            toStatus = SlotStatus.UNAVAILABLE;
            updated = slotRepository.expireBlockedHoldIfDue(
                    slotId, reservationId, now, SlotStatus.HELD, SlotStatus.UNAVAILABLE);
        }
        if (updated == 0) {
            log.debug("HOLD 만료 대상 아님(이미 확정·해제됨), 건너뜀. slotId={}, reservationId={}",
                    slotId, reservationId);
            return false;
        }

        // 스케줄러와 BookSlot(만료 후 도착) 양쪽에서 호출되지만, 만료는 시간이 지나 생긴 결과라 시스템 변경으로 남긴다
        slotStatusHistoryRecorder.recordBySystem(slotId, reservationId,
                SlotStatus.HELD, toStatus, SlotStatusChangeReason.HOLD_EXPIRED, now);

        // 결과 이벤트는 slot-results에 key = slotId로 발행한다
        HoldExpiredEvent event = HoldExpiredEvent.of(slotId, reservationId, heldUntil, now);
        outboxEventRepository.save(OutboxEvent.create(event.eventId(), AGGREGATE_TYPE, slotId, RESULT_TOPIC,
                slotId.toString(), HoldExpiredEvent.TYPE, jsonMapper.writeValueAsString(event), now));

        log.info("HOLD 만료 처리. slotId={}, reservationId={}, heldUntil={}", slotId, reservationId, heldUntil);
        return true;
    }
}
