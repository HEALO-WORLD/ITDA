package healoworld.slot_service.application.service;

import healoworld.slot_service.application.dto.SlotTimeRange;
import healoworld.slot_service.domain.entity.EquipmentDowntime;
import healoworld.slot_service.domain.entity.Slot;
import healoworld.slot_service.global.exception.BaseException;
import healoworld.slot_service.global.exception.ErrorCode;
import healoworld.slot_service.infrastructure.repository.EquipmentDowntimeRepository;
import healoworld.slot_service.infrastructure.repository.SlotCreationLockRepository;
import healoworld.slot_service.infrastructure.repository.SlotRepository;
import healoworld.slot_service.presentation.dto.response.SlotCreateResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SlotCreateWriter {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Seoul");

    private final SlotRepository slotRepository;
    private final SlotCreationLockRepository slotCreationLockRepository;
    private final EquipmentDowntimeRepository equipmentDowntimeRepository;
    private final Clock clock;

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public SlotCreateResponse createSlot(
            UUID equipmentId,
            UUID examTypeId,
            LocalDate date,
            LocalTime startTime,
            LocalTime endTime
    ) {
        return createSlots(
                equipmentId,
                examTypeId,
                date,
                List.of(new SlotTimeRange(startTime, endTime))
        ).getFirst();
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public List<SlotCreateResponse> createSlots(
            UUID equipmentId,
            UUID examTypeId,
            LocalDate date,
            List<SlotTimeRange> ranges
    ) {
        if (equipmentId == null || examTypeId == null || date == null) {
            throw new BaseException(ErrorCode.INVALID_INPUT);
        }

        if (ranges == null || ranges.isEmpty()) {
            throw new BaseException(ErrorCode.SLOT_GENERATION_EMPTY);
        }

        slotCreationLockRepository.lockEquipmentShared(equipmentId);
        slotCreationLockRepository.lock(equipmentId, date);

        Instant now = clock.instant();
        boolean batch = ranges.size() > 1;
        List<SlotTimeRange> existingSlots = batch ? slotRepository.findActiveTimeRanges(equipmentId, date) : List.of();
        List<EquipmentDowntime> downtimes = batch ? equipmentDowntimeRepository.findUnrecoveredStartingBefore(equipmentId, date.plusDays(1).atStartOfDay()) : List.of();
        LocalTime previousEnd = null;
        List<Slot> slots = new ArrayList<>();

        for (SlotTimeRange range : ranges) {
            if (range == null
                    || range.startTime() == null
                    || range.endTime() == null) {
                throw new BaseException(ErrorCode.INVALID_INPUT);
            }

            if (!range.startTime().isBefore(range.endTime())) {
                throw new BaseException(ErrorCode.SLOT_INVALID_TIME_RANGE);
            }

            if (previousEnd != null
                    && range.startTime().isBefore(previousEnd)) {
                throw new BaseException(ErrorCode.SLOT_TIME_OVERLAP);
            }

            if (!date.atTime(range.startTime()).atZone(BUSINESS_ZONE)
                    .toInstant().isAfter(now)) {
                throw new BaseException(ErrorCode.SLOT_START_NOT_FUTURE);
            }

            boolean overlapsSlot = batch
                    ? existingSlots.stream().anyMatch(existing ->
                            existing.startTime().isBefore(range.endTime())
                                    && existing.endTime().isAfter(range.startTime()))
                    : slotRepository.existsOverlappingSlot(equipmentId, date, range.startTime(), range.endTime());
            if (overlapsSlot) {
                throw new BaseException(ErrorCode.SLOT_TIME_OVERLAP);
            }

            // 장비 중단 변경과 생성은 장비 잠금으로 조율된다. 시작한 미복구 중단은 예정 종료 후에도 차단한다.
            LocalDateTime start = date.atTime(range.startTime());
            LocalDateTime end = date.atTime(range.endTime());
            LocalDateTime checkedAt = LocalDateTime.now(clock);
            boolean overlapsDowntime = batch
                    ? downtimes.stream().anyMatch(downtime -> downtime.getStartedAt().isBefore(end)
                            && (downtime.getEndedAt().isAfter(start)
                                || !downtime.getStartedAt().isAfter(checkedAt)))
                    : equipmentDowntimeRepository.existsBlockingOverlap(equipmentId, start, end, checkedAt);
            if (overlapsDowntime) {
                throw new BaseException(ErrorCode.SLOT_IN_DOWNTIME);
            }

            slots.add(Slot.create(
                    equipmentId,
                    examTypeId,
                    date,
                    range.startTime(),
                    range.endTime()
            ));

            previousEnd = range.endTime();
        }

        return slotRepository.saveAll(slots).stream()
                .map(SlotCreateResponse::from)
                .toList();
    }
}
