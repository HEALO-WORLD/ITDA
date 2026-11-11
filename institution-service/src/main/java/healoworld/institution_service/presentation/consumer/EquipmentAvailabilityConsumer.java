package healoworld.institution_service.presentation.consumer;

import healoworld.institution_service.application.event.EquipmentAvailabilityChangedEvent;
import healoworld.institution_service.application.service.equipment.EquipmentAvailabilityService;
import healoworld.institution_service.domain.enums.EquipmentStatus;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
@RequiredArgsConstructor
public class EquipmentAvailabilityConsumer {
    private final JsonMapper json;
    private final EquipmentAvailabilityService service;

    @KafkaListener(topics = EquipmentAvailabilityChangedEvent.TOPIC)
    public void consume(ConsumerRecord<String, String> record) {
        EquipmentAvailabilityChangedEvent event = json.readValue(record.value(), EquipmentAvailabilityChangedEvent.class);
        var typeHeader = record.headers().lastHeader("eventType");
        var idHeader = record.headers().lastHeader("eventId");
        if (event == null || event.eventId() == null || event.equipmentId() == null || event.institutionId() == null
                || event.occurredAt() == null || event.changedBy() == null || event.revision() < 1
                || event.reason() == null || event.reason().isBlank() || event.reason().length() > 500
                || event.status() == null || !EquipmentAvailabilityChangedEvent.TYPE.equals(event.eventType())
                || !event.equipmentId().toString().equals(record.key())
                || typeHeader == null || idHeader == null
                || typeHeader.value() == null || idHeader.value() == null
                || !event.eventType().equals(new String(typeHeader.value(), StandardCharsets.UTF_8))
                || !event.eventId().toString().equals(new String(idHeader.value(), StandardCharsets.UTF_8))) {
            throw new IllegalArgumentException("장비 운영 상태 이벤트 계약 위반");
        }
        EquipmentStatus.valueOf(event.status());
        service.apply(event);
    }
}
