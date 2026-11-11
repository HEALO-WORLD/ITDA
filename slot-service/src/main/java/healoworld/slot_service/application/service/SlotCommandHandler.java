package healoworld.slot_service.application.service;

import healoworld.slot_service.application.dto.HoldResult;
import healoworld.slot_service.application.dto.ReleaseOutcome;
import healoworld.slot_service.application.event.command.BookSlotEvent;
import healoworld.slot_service.application.event.command.HoldRequestedEvent;
import healoworld.slot_service.application.event.command.ReleaseHoldEvent;
import healoworld.slot_service.application.event.command.ReleaseSlotEvent;
import healoworld.slot_service.application.event.result.SlotBookFailedEvent;
import healoworld.slot_service.application.event.result.SlotBookedEvent;
import healoworld.slot_service.application.event.result.SlotHeldEvent;
import healoworld.slot_service.application.event.result.SlotHoldFailedEvent;
import healoworld.slot_service.domain.enums.HoldFailureReason;
import healoworld.slot_service.global.metrics.SlotMetrics;
import healoworld.slot_service.global.metrics.SlotMetrics.BookResult;
import healoworld.slot_service.global.metrics.SlotMetrics.HoldOutcome;
import healoworld.slot_service.global.inbox.ConsumedEvent;
import healoworld.slot_service.global.inbox.ConsumedEventId;
import healoworld.slot_service.global.inbox.ConsumedEventRepository;
import healoworld.slot_service.global.outbox.OutboxEvent;
import healoworld.slot_service.global.outbox.OutboxEventRepository;
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
    private final SlotBookService slotBookService;
    private final SlotReleaseService slotReleaseService;
    private final OutboxEventRepository outboxEventRepository;
    private final ConsumedEventRepository consumedEventRepository;
    private final JsonMapper jsonMapper;
    private final SlotMetrics slotMetrics;
    private final Clock clock;

    @Transactional
    public void handleHoldRequested(HoldRequestedEvent event) {
        if (consumedEventRepository.existsById(new ConsumedEventId(event.eventId(), CONSUMER))) {
            log.info("이미 처리한 HoldRequested, 건너뜀. eventId={}", event.eventId());
            return;
        }

        LocalDateTime now = LocalDateTime.now(clock);

        // 슬롯 존재 확인을 따로 하지 않는다. 없는 슬롯은 hold()가 UPDATE 0행 뒤 notFound 결과로 알려준다
        HoldResult result = slotHoldService.hold(event.slotId(), event.reservationId(), event.eventId());
        if (result.isSuccess()) {
            SlotHeldEvent held = SlotHeldEvent.of(event, result.getHeldUntil(), now);
            saveOutbox(held.eventId(), event.slotId(), SlotHeldEvent.TYPE, held, event.occurredAt(), now);
            slotMetrics.hold(result.isAlreadyHeld() ? HoldOutcome.IDEMPOTENT : HoldOutcome.SUCCESS);
        } else if (result.isNotFound()) {
            log.warn("HOLD 요청 슬롯 없음, SlotHoldFailed 발행. slotId={}, reservationId={}",
                    event.slotId(), event.reservationId());
            saveHoldFailed(event, result.getFailureReason(), now);
            slotMetrics.hold(HoldOutcome.NOT_FOUND);
        } else {
            saveHoldFailed(event, result.getFailureReason(), now);
            slotMetrics.hold(HoldOutcome.from(result.getFailureReason()));
        }

        consumedEventRepository.save(ConsumedEvent.create(event.eventId(), CONSUMER, HoldRequestedEvent.TYPE, now));
    }

    @Transactional
    public void handleBookSlot(BookSlotEvent event) {
        if (consumedEventRepository.existsById(new ConsumedEventId(event.eventId(), CONSUMER))) {
            log.info("이미 처리한 BookSlot, 건너뜀. eventId={}", event.eventId());
            return;
        }

        LocalDateTime now = LocalDateTime.now(clock);

        switch (slotBookService.book(event.slotId(), event.reservationId(), event.eventId())) {
            case BOOKED -> {
                saveBooked(event, now);
                slotMetrics.book(BookResult.SUCCESS);
            }
            // 같은 예약으로 이미 확정된 경우도 성공으로 다시 알려 Referral이 확정 처리를 이어갈 수 있게 한다
            case ALREADY_BOOKED -> {
                saveBooked(event, now);
                slotMetrics.book(BookResult.IDEMPOTENT);
            }
            // HoldExpired는 SlotHoldExpiryService가 같은 트랜잭션에서 이미 Outbox에 기록했다
            case HOLD_EXPIRED -> {
                log.info("만료된 HOLD에 BookSlot 도착, 확정하지 않고 HoldExpired 발행. slotId={}, reservationId={}",
                        event.slotId(), event.reservationId());
                slotMetrics.book(BookResult.HOLD_EXPIRED);
            }
            case FAILED -> {
                SlotBookFailedEvent failed = SlotBookFailedEvent.of(event, now);
                saveOutbox(failed.eventId(), event.slotId(), SlotBookFailedEvent.TYPE, failed, event.occurredAt(), now);
                slotMetrics.book(BookResult.FAILED);
            }
        }

        consumedEventRepository.save(ConsumedEvent.create(event.eventId(), CONSUMER, BookSlotEvent.TYPE, now));
    }

    // 결과 이벤트는 발행하지 않는다. Referral은 명령을 보내기 전에 예약을 이미 FAILED로 바꿨다
    @Transactional
    public void handleReleaseHold(ReleaseHoldEvent event) {
        if (consumedEventRepository.existsById(new ConsumedEventId(event.eventId(), CONSUMER))) {
            log.info("이미 처리한 ReleaseHold, 건너뜀. eventId={}", event.eventId());
            return;
        }

        LocalDateTime now = LocalDateTime.now(clock);
        ReleaseOutcome outcome = slotReleaseService.releaseHold(event.slotId(), event.reservationId(), event.eventId());
        log.info("ReleaseHold 처리. slotId={}, reservationId={}, result={}",
                event.slotId(), event.reservationId(), outcome);

        consumedEventRepository.save(ConsumedEvent.create(event.eventId(), CONSUMER, ReleaseHoldEvent.TYPE, now));
    }

    // 결과 이벤트는 발행하지 않는다. Referral은 명령을 보내기 전에 의뢰를 이미 CANCELED로 바꿨다
    @Transactional
    public void handleReleaseSlot(ReleaseSlotEvent event) {
        if (consumedEventRepository.existsById(new ConsumedEventId(event.eventId(), CONSUMER))) {
            log.info("이미 처리한 ReleaseSlot, 건너뜀. eventId={}", event.eventId());
            return;
        }

        LocalDateTime now = LocalDateTime.now(clock);
        ReleaseOutcome outcome = slotReleaseService.releaseSlot(event.slotId(), event.reservationId(), event.eventId());
        log.info("ReleaseSlot 처리. slotId={}, reservationId={}, result={}",
                event.slotId(), event.reservationId(), outcome);

        consumedEventRepository.save(ConsumedEvent.create(event.eventId(), CONSUMER, ReleaseSlotEvent.TYPE, now));
    }

    private void saveBooked(BookSlotEvent event, LocalDateTime now) {
        SlotBookedEvent booked = SlotBookedEvent.of(event, now);
        saveOutbox(booked.eventId(), event.slotId(), SlotBookedEvent.TYPE, booked, event.occurredAt(), now);
    }

    private void saveHoldFailed(HoldRequestedEvent event, HoldFailureReason reason, LocalDateTime now) {
        SlotHoldFailedEvent failed = SlotHoldFailedEvent.of(event, reason, now);
        saveOutbox(failed.eventId(), event.slotId(), SlotHoldFailedEvent.TYPE, failed, event.occurredAt(), now);
    }

    // 결과 이벤트는 slot-results에 key = slotId로 발행한다
    // sourceOccurredAt(받은 명령의 occurredAt)은 발행 시 체류 시간 측정에 쓴다
    private void saveOutbox(UUID eventId, UUID slotId, String eventType, Object payload,
                            LocalDateTime sourceOccurredAt, LocalDateTime now) {
        outboxEventRepository.save(OutboxEvent.create(eventId, AGGREGATE_TYPE, slotId, RESULT_TOPIC,
                slotId.toString(), eventType, jsonMapper.writeValueAsString(payload), sourceOccurredAt, now));
    }
}
