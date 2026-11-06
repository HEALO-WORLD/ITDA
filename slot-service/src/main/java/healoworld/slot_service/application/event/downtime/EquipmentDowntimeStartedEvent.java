package healoworld.slot_service.application.event.downtime;

import healoworld.slot_service.domain.entity.EquipmentDowntime;

import java.time.LocalDateTime;
import java.util.UUID;

// equipment-events로 발행하는 장비 중단 시작 이벤트 (Slot → Referral, key = equipmentId)
// 필드 이름·형식은 Referral의 EquipmentDowntimeStartedEvent·EquipmentDowntimeStartedConsumer 계약과 같다
//  - 시각은 모두 한국 시간(Asia/Seoul) 기준 LocalDateTime
//  - startAt·endAt은 p_equipment_downtimes의 started_at·ended_at
//  - Referral은 eventId를 장애의 source_event_id로 저장해 재전달을 걸러낸다
public record EquipmentDowntimeStartedEvent(
        UUID eventId,
        String eventType,
        LocalDateTime occurredAt,
        UUID equipmentId,
        UUID institutionId,
        String reason,
        LocalDateTime startAt,
        LocalDateTime endAt
) {
    public static final String TYPE = "EquipmentDowntimeStarted";
    public static final String TOPIC = "equipment-events";

    public static EquipmentDowntimeStartedEvent of(EquipmentDowntime downtime, UUID institutionId,
                                                   LocalDateTime occurredAt) {
        return new EquipmentDowntimeStartedEvent(UUID.randomUUID(), TYPE, occurredAt, downtime.getEquipmentId(),
                institutionId, downtime.getReason(), downtime.getStartedAt(), downtime.getEndedAt());
    }
}
