package healoworld.slot_service.application.service;

import healoworld.slot_service.application.dto.EquipmentDowntimeRegistration;
import healoworld.slot_service.application.dto.EquipmentDowntimeRegistration.ConflictedSlot;
import healoworld.slot_service.application.event.downtime.EquipmentDowntimeStartedEvent;
import healoworld.slot_service.domain.entity.EquipmentDowntime;
import healoworld.slot_service.domain.enums.EquipmentDowntimeType;
import healoworld.slot_service.domain.entity.Slot;
import healoworld.slot_service.domain.enums.SlotStatus;
import healoworld.slot_service.domain.enums.SlotStatusChangeReason;
import healoworld.slot_service.global.exception.BaseException;
import healoworld.slot_service.global.exception.ErrorCode;
import healoworld.slot_service.global.outbox.OutboxEvent;
import healoworld.slot_service.global.outbox.OutboxEventRepository;
import healoworld.slot_service.infrastructure.repository.EquipmentDowntimeRepository;
import healoworld.slot_service.infrastructure.repository.SlotCreationLockRepository;
import healoworld.slot_service.infrastructure.repository.SlotRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

// 장비 중단 등록의 DB 변경을 한 트랜잭션(slot_db)으로 묶는다: 중단 이력 저장, 슬롯 차단, 슬롯 상태 이력, Outbox 이벤트
// 원격 호출(소유권 확인)은 호출하는 쪽(EquipmentDowntimeService)이 트랜잭션 밖에서 먼저 끝낸다
//
// 동시성
//  1) 장비 배타 advisory lock을 먼저 얻고 날짜 잠금을 오름차순으로 얻는다.
//     생성·HOLD·BOOK의 장비 공유 잠금 연결은 후속 단계에서 수행한다.
//  2) 겹치는 슬롯 행 잠금(findOverlappingForUpdate): HOLD·BOOK·해제·만료는 행 단위 조건부 UPDATE라
//     이 트랜잭션이 끝날 때까지 기다린 뒤, 바뀐 상태·equipment_downtime_id로 조건을 다시 평가한다
@Slf4j
@Service
@RequiredArgsConstructor
public class EquipmentDowntimeWriter {

    private static final List<SlotStatus> OCCUPIED = List.of(SlotStatus.HELD, SlotStatus.BOOKED);
    private static final String AGGREGATE_TYPE = "EQUIPMENT_DOWNTIME";
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Seoul");

    private final EquipmentDowntimeRepository equipmentDowntimeRepository;
    private final SlotRepository slotRepository;
    private final SlotCreationLockRepository slotCreationLockRepository;
    private final SlotStatusHistoryRecorder slotStatusHistoryRecorder;
    private final OutboxEventRepository outboxEventRepository;
    private final JsonMapper jsonMapper;
    private final Clock clock;
    private final EquipmentAvailabilityPublisher availabilityPublisher;

    // startedAt·endedAt: 한국 시간 기준. changedBy: 등록한 사용자 ID (상태 이력의 changed_by)
    // institutionId: Institution Service에서 확인한 장비의 실제 소속 기관 ID (이벤트에 담는다)
    @Transactional
    public EquipmentDowntimeRegistration register(UUID equipmentId, UUID institutionId, LocalDateTime startedAt,
                                                  LocalDateTime endedAt, String reason, UUID changedBy) {
        return register(equipmentId, institutionId, startedAt, endedAt, reason, changedBy, EquipmentDowntimeType.FAILURE);
    }

    @Transactional
    public EquipmentDowntimeRegistration register(UUID equipmentId, UUID institutionId, LocalDateTime startedAt,
                                                  LocalDateTime endedAt, String reason, UUID changedBy, EquipmentDowntimeType downtimeType) {
        LocalDateTime now = LocalDateTime.now(clock);

        slotCreationLockRepository.lockEquipment(equipmentId);
        lockDates(equipmentId, startedAt, endedAt);

        // 잠금을 얻은 뒤 조회하므로 먼저 커밋된 같은 장비의 중단을 놓치지 않는다
        if (equipmentDowntimeRepository.existsActiveOverlap(equipmentId, startedAt, endedAt)) {
            throw new BaseException(ErrorCode.DOWNTIME_OVERLAP);
        }

        EquipmentDowntime downtime = equipmentDowntimeRepository.saveAndFlush(
                EquipmentDowntime.register(equipmentId, institutionId, downtimeType, startedAt, endedAt, reason, changedBy));
        if (downtime.activate(now)) {
            availabilityPublisher.saveSnapshot(downtime, changedBy, reason, now);
        }
        return blockPeriod(downtime, institutionId, startedAt, endedAt, changedBy, now);
    }

    @Transactional
    public EquipmentDowntimeRegistration blockPeriod(EquipmentDowntime downtime, UUID institutionId,
                                                     LocalDateTime startedAt, LocalDateTime endedAt, UUID changedBy, LocalDateTime now) {
        UUID equipmentId = downtime.getEquipmentId();
        slotCreationLockRepository.lockEquipment(equipmentId);
        lockDates(equipmentId, startedAt, endedAt);

        // 행을 잠근 뒤 읽은 상태라 아래 분류와 UPDATE 사이에 다른 트랜잭션이 상태를 바꿀 수 없다
        List<Slot> slots = slotRepository.findOverlappingForUpdate(equipmentId,
                startedAt.toLocalDate(), startedAt.toLocalTime(), endedAt.toLocalDate(), endedAt.toLocalTime());

        List<UUID> slotIds = new ArrayList<>();
        List<UUID> availableIds = new ArrayList<>();
        List<ConflictedSlot> conflicts = new ArrayList<>();
        for (Slot slot : slots) {
            slotIds.add(slot.getId());
            if (slot.getStatus() == SlotStatus.AVAILABLE) {
                availableIds.add(slot.getId());
            } else if (OCCUPIED.contains(slot.getStatus())) {
                conflicts.add(new ConflictedSlot(slot.getId(), slot.getStatus(), slot.getReservationId()));
            }
            // UNAVAILABLE: 이미 다른 중단으로 막힌 슬롯이라 변경하지 않는다 (처음 표시한 중단 ID 유지)
        }

        if (!slotIds.isEmpty()) {
            int blocked = slotRepository.blockAvailable(slotIds, downtime.getId(), now,
                    SlotStatus.AVAILABLE, SlotStatus.UNAVAILABLE);
            if (blocked != availableIds.size()) {
                // 행 잠금 아래에서는 일어나지 않아야 한다. 이력과 실제 변경이 어긋나지 않도록 전체를 롤백한다
                throw new IllegalStateException("차단한 슬롯 수가 조회 결과와 다릅니다. expected="
                        + availableIds.size() + ", actual=" + blocked);
            }
            slotRepository.markOccupiedAsBlocked(slotIds, downtime.getId(), now, OCCUPIED);
        }

        // 실제로 상태가 바뀐 슬롯(AVAILABLE → UNAVAILABLE)만 이력을 남긴다
        for (UUID slotId : availableIds) {
            slotStatusHistoryRecorder.recordByUser(slotId, null, SlotStatus.AVAILABLE, SlotStatus.UNAVAILABLE,
                    SlotStatusChangeReason.DOWNTIME_STARTED, changedBy, now);
        }

        // 이벤트는 Kafka로 직접 보내지 않고 Outbox에 저장한다. 위 변경과 함께 커밋·롤백되고,
        // 커밋 후 OutboxPublisher가 발행한다 (발행 실패 시 같은 행·같은 eventId로 재시도)
        saveStartedEvent(downtime, institutionId, startedAt, endedAt, now);

        log.info("장비 중단 등록. downtimeId={}, equipmentId={}, blocked={}, conflicted={}",
                downtime.getId(), equipmentId, availableIds.size(), conflicts.size());
        return new EquipmentDowntimeRegistration(downtime, availableIds.size(), List.copyOf(conflicts));
    }

    // topic = equipment-events, key = equipmentId (동일 파티션 배치 기준, Referral Consumer가 key와 본문을 대조함)
    // occurredAt은 한국 시간 기준 등록 시각이다. 저장한 now와 같은 순간이며 시간대만 Asia/Seoul로 고정한다
    private void saveStartedEvent(EquipmentDowntime downtime, UUID institutionId,
                                  LocalDateTime startedAt, LocalDateTime endedAt, LocalDateTime now) {
        LocalDateTime occurredAt = now.atZone(clock.getZone()).withZoneSameInstant(BUSINESS_ZONE).toLocalDateTime();
        EquipmentDowntimeStartedEvent event = new EquipmentDowntimeStartedEvent(UUID.randomUUID(),
                EquipmentDowntimeStartedEvent.TYPE, occurredAt, downtime.getEquipmentId(), institutionId,
                downtime.getReason(), startedAt, endedAt);
        outboxEventRepository.save(OutboxEvent.create(event.eventId(), AGGREGATE_TYPE, downtime.getId(),
                EquipmentDowntimeStartedEvent.TOPIC, downtime.getEquipmentId().toString(),
                EquipmentDowntimeStartedEvent.TYPE, jsonMapper.writeValueAsString(event), now));
    }

    // 중단 구간에 포함된 날짜를 오름차순으로 잠근다. 종료 시각은 포함하지 않으므로 자정에 끝나면 그 날짜는 제외한다
    private void lockDates(UUID equipmentId, LocalDateTime startedAt, LocalDateTime endedAt) {
        LocalDate lastDate = endedAt.minusNanos(1).toLocalDate();
        for (LocalDate date = startedAt.toLocalDate(); !date.isAfter(lastDate); date = date.plusDays(1)) {
            slotCreationLockRepository.lock(equipmentId, date);
        }
    }
}
