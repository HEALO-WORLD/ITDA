package healoworld.slot_service.application.service;

import healoworld.slot_service.domain.entity.EquipmentDowntime;
import healoworld.slot_service.domain.enums.SlotStatus;
import healoworld.slot_service.domain.enums.SlotStatusChangeReason;
import healoworld.slot_service.global.exception.BaseException;
import healoworld.slot_service.global.exception.ErrorCode;
import healoworld.slot_service.infrastructure.repository.EquipmentDowntimeRepository;
import healoworld.slot_service.infrastructure.repository.SlotCreationLockRepository;
import healoworld.slot_service.infrastructure.repository.SlotRepository;
import healoworld.slot_service.presentation.dto.response.EquipmentDowntimeLifecycleResponse;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class EquipmentDowntimeLifecycleWriter {
    private final EquipmentDowntimeRepository downtimes;
    private final SlotCreationLockRepository locks;
    private final SlotRepository slots;
    private final EquipmentDowntimeWriter blocker;
    private final EquipmentAvailabilityPublisher availability;
    private final SlotStatusHistoryRecorder histories;
    private final Clock clock;

    @Transactional
    public void activateDue(UUID equipmentId) {
        locks.lockEquipment(equipmentId);
        LocalDateTime now = LocalDateTime.now(clock);
        EquipmentDowntime source = null;
        for (EquipmentDowntime d : downtimes.findByEquipmentIdAndDeletedAtIsNullAndRecoveredAtIsNull(equipmentId)) {
            if (d.getRegisteredBy() != null && d.activate(now)) {
                source = d;
            }
        }
        if (source != null) {
            availability.saveSnapshot(source, source.getRegisteredBy(), "등록된 장비 중단 시작", now);
        }
    }

    @Transactional
    public EquipmentDowntimeLifecycleResponse recover(UUID id, UUID equipmentId, UUID ownerInstitutionId,
                                                      UUID userId, String reason) {
        locks.lockEquipment(equipmentId);
        EquipmentDowntime d = owned(id, equipmentId, ownerInstitutionId);
        LocalDateTime now = LocalDateTime.now(clock);
        if (d.getStartedAt().isAfter(now)) {
            throw new BaseException(ErrorCode.DOWNTIME_NOT_STARTED);
        }
        if (d.recover(now, userId, reason)) {
            availability.saveSnapshot(d, userId, reason, now);
        }
        reopen(equipmentId, userId, now);
        return EquipmentDowntimeLifecycleResponse.from(d);
    }

    @Transactional
    public EquipmentDowntimeLifecycleResponse extend(UUID id, UUID equipmentId, UUID ownerInstitutionId,
                                                     UUID userId, LocalDateTime newEnd) {
        locks.lockEquipment(equipmentId);
        EquipmentDowntime d = owned(id, equipmentId, ownerInstitutionId);
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime oldEnd = d.getEndedAt();
        if (d.getRecoveredAt() != null || !newEnd.isAfter(oldEnd) || !newEnd.isAfter(now)) {
            throw new BaseException(ErrorCode.DOWNTIME_NOT_EXTENDABLE);
        }
        // 다른 일정과 겹치는 연장은 막는다. 자기 자신의 끝 이후이므로 자기 행은 겹치지 않는다.
        if (downtimes.existsActiveOverlap(equipmentId, oldEnd, newEnd)) {
            throw new BaseException(ErrorCode.DOWNTIME_OVERLAP);
        }
        d.extend(newEnd, now);
        downtimes.flush();
        // 추가 구간만 차단·이벤트 발행. 기존 예약을 복원하거나 전체 구간 이벤트를 재발행하지 않는다.
        blocker.blockPeriod(d, ownerInstitutionId, oldEnd, newEnd, userId, now);
        return EquipmentDowntimeLifecycleResponse.from(d);
    }

    @Transactional
    public void reopenRecovered(UUID equipmentId) {
        locks.lockEquipment(equipmentId);
        reopen(equipmentId, null, LocalDateTime.now(clock));
    }

    private void reopen(UUID equipmentId, UUID userId, LocalDateTime now) {
        for (var slot : slots.findRecoveryCandidatesForUpdate(equipmentId, now.toLocalDate(), now.toLocalTime())) {
            if (downtimes.existsBlockingOverlap(equipmentId, slot.getDate().atTime(slot.getStartTime()),
                    slot.getDate().atTime(slot.getEndTime()), now)) {
                continue;
            }
            slot.reopenAfterRecovery();
            if (userId == null) {
                histories.recordBySystem(slot.getId(), null, SlotStatus.UNAVAILABLE, SlotStatus.AVAILABLE,
                        SlotStatusChangeReason.DOWNTIME_ENDED, now);
            } else {
                histories.recordByUser(slot.getId(), null, SlotStatus.UNAVAILABLE, SlotStatus.AVAILABLE,
                        SlotStatusChangeReason.DOWNTIME_ENDED, userId, now);
            }
        }
    }

    private EquipmentDowntime owned(UUID id, UUID equipmentId, UUID ownerInstitutionId) {
        EquipmentDowntime d = downtimes.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new BaseException(ErrorCode.RESOURCE_NOT_FOUND));
        if (!equipmentId.equals(d.getEquipmentId())
                || (d.getInstitutionId() != null && !ownerInstitutionId.equals(d.getInstitutionId()))) {
            throw new BaseException(ErrorCode.FORBIDDEN);
        }
        return d;
    }
}
