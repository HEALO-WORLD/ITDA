package healoworld.slot_service.application.service;

import healoworld.slot_service.domain.entity.Slot;
import healoworld.slot_service.global.exception.BaseException;
import healoworld.slot_service.global.exception.ErrorCode;
import healoworld.slot_service.infrastructure.repository.SlotRepository;
import healoworld.slot_service.presentation.dto.response.SlotCreateResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SlotCreateWriter {

    private final SlotRepository slotRepository;

    @Transactional
    public SlotCreateResponse createSlot(
            UUID equipmentId,
            UUID examTypeId,
            LocalDate date,
            LocalTime startTime,
            LocalTime endTime
    ) {
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