package healoworld.slot_service.application.service;

import healoworld.slot_service.application.dto.HoldResult;
import healoworld.slot_service.application.event.HoldRequestedEvent;
import healoworld.slot_service.application.event.SlotHeldEvent;
import healoworld.slot_service.application.event.SlotHoldFailedEvent;
import healoworld.slot_service.domain.enums.HoldFailureReason;
import healoworld.slot_service.global.inbox.ConsumedEvent;
import healoworld.slot_service.global.inbox.ConsumedEventId;
import healoworld.slot_service.global.inbox.ConsumedEventRepository;
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

// slot-commands 이벤트 처리. 비즈니스 변경, 결과 Outbox 기록, 소비 기록을 한 트랜잭션에서 수행한다
@Slf4j
@Service
@RequiredArgsConstructor
public class SlotCommandHandler {

    public static final String CONSUMER = "slot-service";
    private static final String AGGREGATE_TYPE = "SLOT";
    private static final String RESULT_TOPIC = "slot-results";

    private final SlotHoldService slotHoldService;
    private final SlotRepository slotRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final ConsumedEventRepository consumedEventRepository;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    @Transactional
    public void handleHoldRequested(HoldRequestedEvent event) {
        if (consumedEventRepository.existsById(new ConsumedEventId(event.eventId(), CONSUMER))) {
            log.info("이미 처리한 HoldRequested, 건너뜀. eventId={}", event.eventId());
            return;
        }

        LocalDateTime now = LocalDateTime.now(clock);

        // 없는 슬롯이면 hold()가 예외를 던져 트랜잭션이 롤백되므로 먼저 확인하고 실패 결과를 보낸다
        if (!slotRepository.existsById(event.slotId())) {
            log.warn("HOLD 요청 슬롯 없음, SlotHoldFailed 발행. slotId={}, reservationId={}",
                    event.slotId(), event.reservationId());
            saveHoldFailed(event, HoldFailureReason.SLOT_CONFLICT, now);
        } else {
            HoldResult result = slotHoldService.hold(event.slotId(), event.reservationId());
            if (result.isSuccess()) {
                SlotHeldEvent held = SlotHeldEvent.of(event, result.getHeldUntil(), now);
                saveOutbox(held.eventId(), event.slotId(), SlotHeldEvent.TYPE, held, now);
            } else {
                saveHoldFailed(event, result.getFailureReason(), now);
            }
        }

        consumedEventRepository.save(ConsumedEvent.create(event.eventId(), CONSUMER, HoldRequestedEvent.TYPE, now));
    }

    private void saveHoldFailed(HoldRequestedEvent event, HoldFailureReason reason, LocalDateTime now) {
        SlotHoldFailedEvent failed = SlotHoldFailedEvent.of(event, reason, now);
        saveOutbox(failed.eventId(), event.slotId(), SlotHoldFailedEvent.TYPE, failed, now);
    }

    // 결과 이벤트는 slot-results에 key = slotId로 발행한다
    private void saveOutbox(UUID eventId, UUID slotId, String eventType, Object payload, LocalDateTime now) {
        outboxEventRepository.save(OutboxEvent.create(eventId, AGGREGATE_TYPE, slotId, RESULT_TOPIC,
                slotId.toString(), eventType, jsonMapper.writeValueAsString(payload), now));
    }
}
