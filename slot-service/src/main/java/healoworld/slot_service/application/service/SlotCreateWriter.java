package healoworld.slot_service.application.service;

import healoworld.slot_service.application.dto.SlotTimeRange;
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

            if (slotRepository.existsOverlappingSlot(
                    equipmentId,
                    date,
                    range.startTime(),
                    range.endTime()
            )) {
                throw new BaseException(ErrorCode.SLOT_TIME_OVERLAP);
            }

            // 장비 중단 등록도 같은 장비·날짜의 advisory lock을 잡으므로, 잠금 획득 후의 이 조회는
            // 먼저 커밋된 중단을 반드시 본다 (READ COMMITTED에서는 쿼리마다 새 스냅샷을 쓴다)
            if (equipmentDowntimeRepository.existsBlockingOverlap(
                    equipmentId,
                    date.atTime(range.startTime()),
                    date.atTime(range.endTime()), LocalDateTime.now(clock)
            )) {
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
