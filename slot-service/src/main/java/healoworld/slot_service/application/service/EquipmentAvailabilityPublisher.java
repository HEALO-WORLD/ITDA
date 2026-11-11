package healoworld.slot_service.application.service;

import healoworld.slot_service.application.event.downtime.EquipmentAvailabilityChangedEvent;
import healoworld.slot_service.domain.entity.EquipmentDowntime;
import healoworld.slot_service.domain.entity.EquipmentDowntimeRevision;
import healoworld.slot_service.domain.enums.EquipmentDowntimeType;
import healoworld.slot_service.global.outbox.OutboxEvent;
import healoworld.slot_service.global.outbox.OutboxEventRepository;
import healoworld.slot_service.infrastructure.repository.EquipmentDowntimeRepository;
import healoworld.slot_service.infrastructure.repository.EquipmentDowntimeRevisionRepository;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
@RequiredArgsConstructor
public class EquipmentAvailabilityPublisher {
    private final EquipmentDowntimeRepository downtimes;
    private final EquipmentDowntimeRevisionRepository revisions;
    private final OutboxEventRepository outbox;
    private final JsonMapper json;

    @Transactional(propagation = Propagation.MANDATORY)
    public void saveSnapshot(EquipmentDowntime source, UUID changedBy, String reason, LocalDateTime now) {
        // 장비 잠금 아래에서 전체 활성 중단을 합산한다. 개별 중단의 복구 이벤트로 정상 상태를 단정하지 않는다.
        var active = downtimes.findByEquipmentIdAndDeletedAtIsNullAndRecoveredAtIsNull(source.getEquipmentId())
                .stream().filter(d -> !d.getStartedAt().isAfter(now)).toList();

        String status = active.stream().anyMatch(d -> d.getDowntimeType() == EquipmentDowntimeType.FAILURE)
                ? "FAILED" : active.isEmpty() ? "OPERATIONAL" : "MAINTENANCE";

        EquipmentDowntimeRevision revision = revisions.findById(source.getEquipmentId())
                .orElseGet(() -> EquipmentDowntimeRevision.create(source.getEquipmentId()));

        long version = revision.next();
        revisions.save(revision);

        EquipmentAvailabilityChangedEvent event = new EquipmentAvailabilityChangedEvent(UUID.randomUUID(),
                EquipmentAvailabilityChangedEvent.TYPE, now, source.getEquipmentId(), source.getInstitutionId(),
                version, status, changedBy, reason);

        outbox.save(OutboxEvent.create(event.eventId(), "EQUIPMENT_AVAILABILITY", source.getEquipmentId(),
                EquipmentAvailabilityChangedEvent.TOPIC, source.getEquipmentId().toString(), event.eventType(),
                json.writeValueAsString(event), now));
    }
}
