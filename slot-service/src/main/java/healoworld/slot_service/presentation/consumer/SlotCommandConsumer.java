package healoworld.slot_service.presentation.consumer;

import healoworld.slot_service.application.event.HoldRequestedEvent;
import healoworld.slot_service.application.service.SlotCommandHandler;
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

    private final SlotCommandHandler slotCommandHandler;
    private final JsonMapper jsonMapper;

    @KafkaListener(topics = "slot-commands")
    public void consume(ConsumerRecord<String, String> record) {
        String eventType = header(record, "eventType");
        log.debug("slot-commands 수신. type={}, key={}, offset={}", eventType, record.key(), record.offset());

        switch (eventType) {
            case HoldRequestedEvent.TYPE -> slotCommandHandler.handleHoldRequested(toHoldRequested(record));
            // BookSlot, ReleaseHold, ReleaseSlot은 후속 작업에서 추가
            default -> throw new IllegalArgumentException("지원하지 않는 이벤트 유형입니다: " + eventType);
        }
    }

    private HoldRequestedEvent toHoldRequested(ConsumerRecord<String, String> record) {
        HoldRequestedEvent event = jsonMapper.readValue(record.value(), HoldRequestedEvent.class);

        if (event.eventId() == null || event.slotId() == null || event.reservationId() == null) {
            throw new IllegalArgumentException("HoldRequested 필수 필드가 없습니다: " + record.value());
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
