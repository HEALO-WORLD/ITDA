package healoworld.slot_service.presentation.consumer;

import healoworld.slot_service.application.event.command.BookSlotEvent;
import healoworld.slot_service.application.event.command.HoldRequestedEvent;
import healoworld.slot_service.application.event.command.ReleaseHoldEvent;
import healoworld.slot_service.application.event.command.ReleaseSlotEvent;
import healoworld.slot_service.application.event.command.SlotCommand;
import healoworld.slot_service.application.service.SlotCommandHandler;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;

// slot-commands 수신: 메시지를 검증하고 eventType별 핸들러로 넘긴다
// 검증 실패(IllegalArgumentException)와 JSON 오류(JacksonException)는 재시도 없이 slot-commands-dlt로 간다
@Slf4j
@Component
@RequiredArgsConstructor
public class SlotCommandConsumer {

    static final String COMMAND_DURATION = "slot.command.duration";

    private final SlotCommandHandler slotCommandHandler;
    private final JsonMapper jsonMapper;
    private final MeterRegistry meterRegistry;

    @KafkaListener(topics = "slot-commands")
    public void consume(ConsumerRecord<String, String> record) {
        String eventType = header(record, "eventType");
        log.debug("slot-commands 수신. type={}, key={}, offset={}", eventType, record.key(), record.offset());

        switch (eventType) {
            case HoldRequestedEvent.TYPE -> {
                HoldRequestedEvent event = read(record, HoldRequestedEvent.class, eventType);
                timed(eventType, () -> slotCommandHandler.handleHoldRequested(event));
            }
            case BookSlotEvent.TYPE -> {
                BookSlotEvent event = read(record, BookSlotEvent.class, eventType);
                timed(eventType, () -> slotCommandHandler.handleBookSlot(event));
            }
            case ReleaseHoldEvent.TYPE -> {
                ReleaseHoldEvent event = read(record, ReleaseHoldEvent.class, eventType);
                timed(eventType, () -> slotCommandHandler.handleReleaseHold(event));
            }
            case ReleaseSlotEvent.TYPE -> {
                ReleaseSlotEvent event = read(record, ReleaseSlotEvent.class, eventType);
                timed(eventType, () -> slotCommandHandler.handleReleaseSlot(event));
            }
            default -> throw new IllegalArgumentException("지원하지 않는 이벤트 유형입니다: " + eventType);
        }
    }

    // 처리 시간: 핸들러 호출부터 트랜잭션 커밋까지 (JSON 파싱·검증은 제외)
    // 재시도하면 시도마다 따로 기록하고, 예외로 끝난 시도는 outcome=error로 남긴다
    private void timed(String eventType, Runnable handler) {
        Timer.Sample sample = Timer.start(meterRegistry);
        String outcome = "success";
        try {
            handler.run();
        } catch (RuntimeException e) {
            outcome = "error";
            throw e;
        } finally {
            sample.stop(Timer.builder(COMMAND_DURATION)
                    .description("slot-commands 핸들러 처리 시간 (트랜잭션 커밋 포함)")
                    .tag("type", eventType)
                    .tag("outcome", outcome)
                    .publishPercentileHistogram()   // Grafana에서 p50/p95 계산용 bucket 노출
                    .register(meterRegistry));
        }
    }

    // JSON을 읽고 필수 필드(eventId, slotId, reservationId)와 메시지 키(= slotId)를 검증한다
    private <T extends SlotCommand> T read(ConsumerRecord<String, String> record, Class<T> type, String eventType) {
        T event = jsonMapper.readValue(record.value(), type);

        if (event.eventId() == null || event.slotId() == null || event.reservationId() == null) {
            throw new IllegalArgumentException(eventType + " 필수 필드가 없습니다: " + record.value());
        }
        if (!event.slotId().toString().equals(record.key())) {
            throw new IllegalArgumentException("메시지 키와 slotId가 다릅니다. key=" + record.key() + ", slotId=" + event.slotId());
        }
        return event;
    }

    private String header(ConsumerRecord<String, String> record, String key) {
        Header header = record.headers().lastHeader(key);
        if (header == null) {
            throw new IllegalArgumentException("필수 헤더가 없습니다: " + key);
        }
        return new String(header.value(), StandardCharsets.UTF_8);
    }
}
