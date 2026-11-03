package healoworld.referral_service.presentation.consumer;

import healoworld.referral_service.application.event.EquipmentDowntimeStartedEvent;
import healoworld.referral_service.application.service.EquipmentIncidentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class EquipmentDowntimeStartedConsumer {

    private final EquipmentIncidentService equipmentIncidentService;
    private final JsonMapper jsonMapper;

    @KafkaListener(topics = "equipment-events")
    public void consume(ConsumerRecord<String, String> record) {

        // 1. Kafka 헤더 검증
        Header header = record.headers().lastHeader("eventType");

        if (header == null) {
            throw new IllegalArgumentException("eventType 헤더가 없습니다.");
        }

        String eventType = new String(header.value(), StandardCharsets.UTF_8);

        if (!EquipmentDowntimeStartedEvent.TYPE.equals(eventType)) {
            throw new IllegalArgumentException("지원하지 않는 이벤트 유형: " + eventType);
        }

        // 2. JSON -> Event 객체 변환
        EquipmentDowntimeStartedEvent event = jsonMapper.readValue(
                record.value(),
                EquipmentDowntimeStartedEvent.class
        );

        // 3. 필수 필드 검증
        validate(event, record.key());

        // 4. 장비 장애 등록
        UUID incidentId = equipmentIncidentService.register(
                event.eventId(),
                event.equipmentId(),
                event.institutionId(),
                event.reason(),
                event.occurredAt(),
                event.startAt(),
                event.endAt()
        );

        // 5. 영향받는 예약의 후속 처리 작업 생성
        equipmentIncidentService.createPendingReferrals(incidentId);

        log.info("장비 장애 이벤트 처리 완료. eventId={}, incidentId={}",
                event.eventId(), incidentId);
    }

    private void validate(EquipmentDowntimeStartedEvent event, String key) {

        if (event == null
                || event.eventId() == null
                || event.equipmentId() == null
                || event.institutionId() == null
                || event.occurredAt() == null
                || event.startAt() == null
                || event.endAt() == null
                || event.reason() == null
                || event.reason().isBlank()) {
            throw new IllegalArgumentException("장비 장애 이벤트 필수 필드가 누락되었습니다.");
        }

        if (!EquipmentDowntimeStartedEvent.TYPE.equals(event.eventType())) {
            throw new IllegalArgumentException("이벤트 본문의 eventType이 올바르지 않습니다.");
        }

        if (!event.equipmentId().toString().equals(key)) {
            throw new IllegalArgumentException("Kafka 메시지 키와 equipmentId가 일치하지 않습니다.");
        }
    }
}
