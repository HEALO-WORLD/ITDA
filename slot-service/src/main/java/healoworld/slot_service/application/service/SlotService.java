package healoworld.slot_service.application.service;

import healoworld.slot_service.application.dto.SlotTimeRange;
import healoworld.slot_service.domain.entity.Slot;
import healoworld.slot_service.domain.enums.Role;
import healoworld.slot_service.domain.enums.SlotStatus;
import healoworld.slot_service.domain.enums.SlotView;
import healoworld.slot_service.global.dto.SliceResponse;
import healoworld.slot_service.global.exception.BaseException;
import healoworld.slot_service.global.exception.ErrorCode;
import healoworld.slot_service.infrastructure.client.EquipmentSlotContextClient;
import healoworld.slot_service.infrastructure.client.dto.EquipmentSlotContextResponse;
import healoworld.slot_service.infrastructure.repository.SlotRepository;
import healoworld.slot_service.presentation.dto.request.SlotCreateRequest;
import healoworld.slot_service.presentation.dto.request.SlotGenerateRequest;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SlotService {

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Seoul");

    private final SlotRepository slotRepository;
    private final EquipmentSlotContextClient equipmentSlotContextClient;
    private final SlotCreateWriter slotCreateWriter;
    private final Clock clock;

    private final SlotGenerationPlanner slotGenerationPlanner;

    public SlotCreateResponse createSlot(
            SlotCreateRequest request,
            UUID userId,
            UUID userInstitutionId,
            Role userRole
    ) {
        if (userId == null || userInstitutionId == null || userRole == null) {
            throw new BaseException(ErrorCode.INVALID_INPUT);
        }

        if (userRole != Role.EXAMINER && userRole != Role.ADMIN) {
            throw new BaseException(ErrorCode.FORBIDDEN);
        }

        validateRequiredFields(request);

        ZonedDateTime startsAt = request.getStartsAt()
                .atZoneSameInstant(BUSINESS_ZONE);

        ZonedDateTime endsAt = request.getEndsAt()
                .atZoneSameInstant(BUSINESS_ZONE);

        validateTimeRange(startsAt, endsAt);

        EquipmentSlotContextResponse context = equipmentSlotContextClient.getSlotContext(
                request.getEquipmentId(),
                request.getExamTypeId(),
                startsAt.toLocalDate()
        );

        if (userRole == Role.EXAMINER
                && !userInstitutionId.equals(context.institutionId())) {
            throw new BaseException(ErrorCode.FORBIDDEN);
        }

        LocalTime startTime = startsAt.toLocalTime();
        LocalTime endTime = endsAt.toLocalTime();

        if (startTime.isBefore(context.operatingStartTime())
                || endTime.isAfter(context.operatingEndTime())) {
            throw new BaseException(ErrorCode.SLOT_OUTSIDE_OPERATING_HOURS);
        }

        Duration slotDuration = Duration.between(
                startsAt.toInstant(),
                endsAt.toInstant()
        );

        Duration examDuration = Duration.ofMinutes(context.durationMinutes());

        if (slotDuration.compareTo(examDuration) < 0) {
            throw new BaseException(ErrorCode.SLOT_DURATION_MISMATCH);
        }

        return slotCreateWriter.createSlot(
                request.getEquipmentId(),
                request.getExamTypeId(),
                startsAt.toLocalDate(),
                startTime,
                endTime
        );
    }

    @Transactional(readOnly = true)
    public SlotDetailResponse getSlot(UUID slotId) {
        Slot slot = slotRepository.findByIdAndDeletedAtIsNull(slotId)
                .orElseThrow(() -> new BaseException(ErrorCode.RESOURCE_NOT_FOUND));

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

        boolean onlyFuture = request.getView() == SlotView.SEARCH;
        ZonedDateTime now = clock.instant().atZone(BUSINESS_ZONE);
        SlotStatus status = onlyFuture ? SlotStatus.AVAILABLE : request.getStatus();

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
                onlyFuture,
                now.toLocalDate(),
                now.toLocalTime(),
                pageable
        );

        Slice<SlotDetailResponse> responses = slots.map(SlotDetailResponse::from);

        return SliceResponse.from(responses);
    }

    public List<SlotCreateResponse> generateSlots(
            SlotGenerateRequest request,
            UUID userId,
            UUID userInstitutionId,
            Role userRole
    ) {
        validateCreationActor(userId, userInstitutionId, userRole);

        if (request == null || request.getEquipmentId() == null
                || request.getExamTypeId() == null || request.getDate() == null) {
            throw new BaseException(ErrorCode.INVALID_INPUT);
        }

        LocalDate today = clock.instant().atZone(BUSINESS_ZONE).toLocalDate();
        if (request.getDate().isBefore(today)) {
            throw new BaseException(ErrorCode.SLOT_START_NOT_FUTURE);
        }

        EquipmentSlotContextResponse context =
                equipmentSlotContextClient.getSlotContext(
                        request.getEquipmentId(),
                        request.getExamTypeId(),
                        request.getDate()
                );

        validateEquipmentOwnership(context, userInstitutionId, userRole);

        List<SlotTimeRange> candidates = slotGenerationPlanner.plan(
                context.operatingStartTime(),
                context.operatingEndTime(),
                context.durationMinutes()
        );

        Instant now = clock.instant();
        List<SlotTimeRange> ranges = new ArrayList<>();

        for (SlotTimeRange range : candidates) {
            if (request.getDate().atTime(range.startTime()).atZone(BUSINESS_ZONE)
                    .toInstant().isAfter(now)) {
                ranges.add(range);
            }
        }

        if (ranges.isEmpty()) {
            throw new BaseException(ErrorCode.SLOT_GENERATION_EMPTY);
        }

        return slotCreateWriter.createSlots(
                request.getEquipmentId(),
                request.getExamTypeId(),
                request.getDate(),
                ranges
        );
    }

    private void validateCreationActor(
            UUID userId,
            UUID institutionId,
            Role role
    ) {
        if (userId == null || institutionId == null || role == null) {
            throw new BaseException(ErrorCode.INVALID_INPUT);
        }

        if (role != Role.EXAMINER && role != Role.ADMIN) {
            throw new BaseException(ErrorCode.FORBIDDEN);
        }
    }

    private void validateEquipmentOwnership(
            EquipmentSlotContextResponse context,
            UUID institutionId,
            Role role
    ) {
        if (role == Role.EXAMINER
                && !institutionId.equals(context.institutionId())) {
            throw new BaseException(ErrorCode.FORBIDDEN);
        }
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

        if (!startsAt.toInstant().isAfter(clock.instant())) {
            throw new BaseException(ErrorCode.SLOT_START_NOT_FUTURE);
        }

        if (!endsAt.isAfter(startsAt)) {
            throw new BaseException(ErrorCode.SLOT_INVALID_TIME_RANGE);
        }

        if (!startsAt.toLocalDate().equals(endsAt.toLocalDate())) {
            throw new BaseException(ErrorCode.SLOT_DATE_MISMATCH);
        }
    }

    public void validateSearchRequest(SlotSearchRequest request) {
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