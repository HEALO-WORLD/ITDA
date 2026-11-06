package healoworld.slot_service.application.dto;

import healoworld.slot_service.domain.entity.EquipmentDowntime;
import healoworld.slot_service.domain.enums.SlotStatus;

import java.util.List;
import java.util.UUID;

// 장비 중단 등록 결과
// blockedSlotCount: 이번 등록으로 AVAILABLE → UNAVAILABLE이 된 슬롯 수
// conflictedSlots: 중단 구간과 겹치지만 예약이 잡혀 있어 상태를 유지한 HELD·BOOKED 슬롯 (Referral 후속 처리 대상)
public record EquipmentDowntimeRegistration(
        EquipmentDowntime downtime,
        int blockedSlotCount,
        List<ConflictedSlot> conflictedSlots
) {

    public record ConflictedSlot(UUID slotId, SlotStatus status, UUID reservationId) {
    }
}
