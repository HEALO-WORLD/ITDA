package healoworld.slot_service.application.service;

import healoworld.slot_service.domain.entity.Slot;
import healoworld.slot_service.global.exception.BaseException;
import healoworld.slot_service.global.exception.ErrorCode;
import healoworld.slot_service.infrastructure.repository.SlotCreationLockRepository;
import healoworld.slot_service.infrastructure.repository.SlotRepository;
import healoworld.slot_service.presentation.dto.response.SlotCreateResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SlotCreateWriter {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Seoul");

    private final SlotRepository slotRepository;
    private final SlotCreationLockRepository slotCreationLockRepository;
    private final Clock clock;

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public SlotCreateResponse createSlot(
            UUID equipmentId,
            UUID examTypeId,
            LocalDate date,
            LocalTime startTime,
            LocalTime endTime
    ) {
        slotCreationLockRepository.lock(equipmentId, date);

        if (!date.atTime(startTime).atZone(BUSINESS_ZONE).toInstant().isAfter(clock.instant())) {
            throw new BaseException(ErrorCode.SLOT_START_NOT_FUTURE);
        }

        if (slotRepository.existsOverlappingSlot(
                equipmentId,
                date,
                startTime,
                endTime
        )) {
            throw new BaseException(ErrorCode.SLOT_TIME_OVERLAP);
        }

        Slot slot = Slot.create(
                equipmentId,
                examTypeId,
                date,
                startTime,
                endTime
        );

        return SlotCreateResponse.from(slotRepository.save(slot));
    }
}