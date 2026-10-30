package healoworld.slot_service.application.service;

import healoworld.slot_service.domain.entity.Slot;
import healoworld.slot_service.domain.enums.SlotStatus;
import healoworld.slot_service.domain.enums.SlotView;
import healoworld.slot_service.global.dto.SliceResponse;
import healoworld.slot_service.global.exception.BaseException;
import healoworld.slot_service.global.exception.ErrorCode;
import healoworld.slot_service.infrastructure.repository.SlotRepository;
import healoworld.slot_service.presentation.dto.request.SlotCreateRequest;
import healoworld.slot_service.presentation.dto.request.SlotSearchRequest;
import healoworld.slot_service.presentation.dto.response.SlotCreateResponse;
import healoworld.slot_service.presentation.dto.response.SlotDetailResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SlotService {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Seoul");

    private final SlotRepository slotRepository;

    @Transactional
    public SlotCreateResponse createSlot(SlotCreateRequest request) {
        validateRequiredFields(request);

        ZonedDateTime startsAt = request.getStartsAt()
                .atZoneSameInstant(BUSINESS_ZONE);

        ZonedDateTime endsAt = request.getEndsAt()
                .atZoneSameInstant(BUSINESS_ZONE);

        validateTimeRange(startsAt, endsAt);

        LocalDate date = startsAt.toLocalDate();
        LocalTime startTime = startsAt.toLocalTime();
        LocalTime endTime = endsAt.toLocalTime();

        boolean overlapping = slotRepository.existsOverlappingSlot(
                request.getEquipmentId(),
                date,
                startTime,
                endTime
        );

        if (overlapping) {
            throw new BaseException(ErrorCode.SLOT_TIME_OVERLAP);
        }

        Slot slot = Slot.create(
                request.getEquipmentId(),
                request.getExamTypeId(),
                date,
                startTime,
                endTime
        );

        Slot savedSlot = slotRepository.save(slot);

        return SlotCreateResponse.from(savedSlot);
    }

    @Transactional(readOnly = true)
    public SlotDetailResponse getSlot(UUID slotId) {
        Slot slot = slotRepository.findById(slotId).orElseThrow(() ->
                        new BaseException(ErrorCode.RESOURCE_NOT_FOUND)
                );

        return SlotDetailResponse.from(slot);
    }

    @Transactional(readOnly = true)
    public SliceResponse<SlotDetailResponse> searchSlots(
            SlotSearchRequest request,
            Set<UUID> visibleEquipmentIds
    ) {
        validateSearchRequest(request);

        if (visibleEquipmentIds == null) {
            throw new BaseException(ErrorCode.REFERENCE_DATA_NOT_READY);
        }

        ZonedDateTime from = request.getFrom()
                .atZoneSameInstant(BUSINESS_ZONE);

        ZonedDateTime to = request.getTo()
                .atZoneSameInstant(BUSINESS_ZONE);

        SlotStatus status = request.getStatus();

        if (request.getView() == SlotView.SEARCH) {
            status = SlotStatus.AVAILABLE;

            ZonedDateTime now = ZonedDateTime.now(BUSINESS_ZONE);

            if (!from.isAfter(now)) {
                from = now.plusNanos(1);
            }
        }

        if (visibleEquipmentIds.isEmpty() || !from.isBefore(to)) {
            return new SliceResponse<>(
                    List.of(),
                    request.getPage(),
                    request.getSize(),
                    false
            );
        }

        Pageable pageable = PageRequest.of(request.getPage(), request.getSize());

        Slice<Slot> slots = slotRepository.searchSlots(
                from.toLocalDate(),
                from.toLocalTime(),
                to.toLocalDate(),
                to.toLocalTime(),
                request.getEquipmentId(),
                request.getExamTypeId(),
                status,
                visibleEquipmentIds,
                pageable
        );

        Slice<SlotDetailResponse> responses = slots.map(SlotDetailResponse::from);

        return SliceResponse.from(responses);
    }

    private void validateRequiredFields(SlotCreateRequest request) {
        if (request == null
                || request.getEquipmentId() == null
                || request.getExamTypeId() == null
                || request.getStartsAt() == null
                || request.getEndsAt() == null) {
            throw new BaseException(ErrorCode.INVALID_INPUT);
        }
    }

    private void validateTimeRange(ZonedDateTime startsAt, ZonedDateTime endsAt) {
        if (!startsAt.toInstant().isAfter(OffsetDateTime.now().toInstant())) {
            throw new BaseException(ErrorCode.SLOT_START_NOT_FUTURE);
        }

        if (!endsAt.isAfter(startsAt)) {
            throw new BaseException(ErrorCode.SLOT_INVALID_TIME_RANGE);
        }

        if (!startsAt.toLocalDate().equals(endsAt.toLocalDate())) {
            throw new BaseException(ErrorCode.SLOT_DATE_MISMATCH);
        }
    }

    private void validateSearchRequest(SlotSearchRequest request) {
        if (request == null
                || request.getFrom() == null
                || request.getTo() == null
                || request.getView() == null
                || request.getPage() == null
                || request.getSize() == null) {
            throw new BaseException(ErrorCode.INVALID_INPUT);
        }

        if (request.getPage() < 0
                || request.getSize() < 1
                || request.getSize() > 100) {
            throw new BaseException(ErrorCode.INVALID_INPUT);
        }

        if (!request.getFrom().isBefore(request.getTo())) {
            throw new BaseException(ErrorCode.SLOT_INVALID_SEARCH_RANGE);
        }

        Duration range = Duration.between(request.getFrom().toInstant(), request.getTo().toInstant());

        if (range.compareTo(Duration.ofDays(31)) > 0) {
            throw new BaseException(ErrorCode.SLOT_SEARCH_RANGE_EXCEEDED);
        }

        if (request.getView() == SlotView.SEARCH
                && request.getStatus() != null
                && request.getStatus() != SlotStatus.AVAILABLE) {
            throw new BaseException(ErrorCode.SLOT_INVALID_SEARCH_STATUS);
        }
    }
}