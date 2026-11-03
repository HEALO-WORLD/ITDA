package healoworld.referral_service.presentation.messaging;

import healoworld.referral_service.application.service.SlotResultService;
import healoworld.referral_service.global.outbox.ReservationEventPayload;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

@RequiredArgsConstructor
@Slf4j
@Component
public class SlotResultListener {
    private final SlotResultService slotResultService;
    private final JsonMapper jsonMapper;

    @KafkaListener(topics = "slot-results")
    public void onSlotResult(@Header("eventId") String id,
                             @Header("eventType") String eventType,
                             @Payload String payload) {

        log.info("slot-results 수신. eventId={}, eventType={}", id, eventType);

        UUID eventId = UUID.fromString(id);
        ReservationEventPayload payloadJson = jsonMapper.readValue(payload, ReservationEventPayload.class);
        log.debug("slot-results 파싱 완료. eventId={}, reservationId={}", id, payloadJson.reservationId());

        if (payloadJson.reservationId() == null) {
            if ("SlotsBlocked".equals(eventType)) {
                log.info("예약 없는 슬롯 차단, 처리 생략. eventId={}, slotId={}", id, payloadJson.slotId());
                return;
            }
            throw new IllegalArgumentException("reservationId 누락. eventType=" + eventType);
        }

        slotResultService.handle(eventId, eventType, payloadJson.reservationId());
    }
}
