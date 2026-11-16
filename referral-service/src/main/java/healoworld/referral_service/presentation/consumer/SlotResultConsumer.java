package healoworld.referral_service.presentation.consumer;

import healoworld.referral_service.application.event.result.SlotResultMessage;
import healoworld.referral_service.application.event.result.SlotResultType;
import healoworld.referral_service.application.service.SlotResultService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.util.Optional;
import java.util.UUID;

@RequiredArgsConstructor
@Slf4j
@Component
public class SlotResultConsumer {
    private final SlotResultService slotResultService;
    private final JsonMapper jsonMapper;

    static final String LISTENER_ID = "slotResultListener";

    // id는 테스트에서 컨테이너를 찾기 위한 것이다. idIsGroup = false라 소비 그룹은 spring.kafka.consumer.group-id 그대로다
    @KafkaListener(id = LISTENER_ID, idIsGroup = false, topics = "slot-results",
            concurrency = "${itda.kafka.slot-results.concurrency:3}")
    public void consume(@Header("eventId") String id,
                             @Header("eventType") String eventType,
                             @Payload String payload) {

        log.info("slot-results 수신. eventId={}, eventType={}", id, eventType);

        UUID eventId = UUID.fromString(id);

        Optional<SlotResultType> type = SlotResultType.from(eventType);
        if (type.isEmpty()) {
            log.warn("slot-results 알 수 없는 eventType, 처리 생략. eventId={}, eventType={}", id, eventType);
            return;
        }

        SlotResultMessage message = jsonMapper.readValue(payload, SlotResultMessage.class);
        log.debug("slot-results 파싱 완료. eventId={}, reservationId={}", id, message.reservationId());

        if (message.reservationId() == null) {
            if (type.get() == SlotResultType.SLOTS_BLOCKED) {
                log.info("예약 없는 슬롯 차단, 처리 생략. eventId={}, slotId={}", id, message.slotId());
                return;
            }
            throw new IllegalArgumentException("reservationId 누락. eventType=" + eventType);
        }

        slotResultService.handle(eventId, type.get(), message.reservationId());
    }
}
