package healoworld.slot_service.application.service;

import healoworld.slot_service.domain.enums.SlotView;
import healoworld.slot_service.global.dto.SliceResponse;
import healoworld.slot_service.global.exception.BaseException;
import healoworld.slot_service.global.exception.ErrorCode;
import healoworld.slot_service.infrastructure.client.EquipmentVisibilityClient;
import healoworld.slot_service.presentation.dto.request.SlotSearchRequest;
import healoworld.slot_service.presentation.dto.response.SlotDetailResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SlotQueryService {

    private final SlotService slotService;
    private final EquipmentVisibilityClient equipmentVisibilityClient;

    public SliceResponse<SlotDetailResponse> searchSlots(
            SlotSearchRequest request,
            UUID userId,
            UUID userInstitutionId,
            String role
    ) {
        validateActor(userId, userInstitutionId, role);
        slotService.validateSearchRequest(request);

        if ("REQUESTER".equals(role)
                && request.getView() == SlotView.MANAGEMENT) {
            throw new BaseException(ErrorCode.FORBIDDEN);
        }

        Set<UUID> visibleEquipmentIds =
                equipmentVisibilityClient.getVisibleEquipmentIds(
                        userInstitutionId,
                        role,
                        request.getInstitutionId(),
                        request.getView() == SlotView.SEARCH
                );

        return slotService.searchSlots(request, visibleEquipmentIds);
    }

    public SlotDetailResponse getSlot(
            UUID slotId,
            UUID userId,
            UUID userInstitutionId,
            String role
    ) {
        validateActor(userId, userInstitutionId, role);

        if (slotId == null) {
            throw new BaseException(ErrorCode.INVALID_INPUT);
        }

        SlotDetailResponse response = slotService.getSlot(slotId);

        Set<UUID> visibleEquipmentIds =
                equipmentVisibilityClient.getVisibleEquipmentIds(
                        userInstitutionId,
                        role,
                        null,
                        "REQUESTER".equals(role)
                );

        if (!visibleEquipmentIds.contains(response.getEquipmentId())) {
            throw new BaseException(ErrorCode.FORBIDDEN);
        }

        return response;
    }

    private void validateActor(
            UUID userId,
            UUID institutionId,
            String role
    ) {
        if (userId == null || institutionId == null || role == null) {
            throw new BaseException(ErrorCode.INVALID_INPUT);
        }

        if (!Set.of("REQUESTER", "EXAMINER", "ADMIN").contains(role)) {
            throw new BaseException(ErrorCode.FORBIDDEN);
        }
    }
}