package healoworld.slot_service.infrastructure.client;

import feign.FeignException;
import healoworld.slot_service.global.dto.ApiResponse;
import healoworld.slot_service.global.exception.BaseException;
import healoworld.slot_service.global.exception.ErrorCode;
import healoworld.slot_service.infrastructure.client.dto.EquipmentSlotContextResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class EquipmentSlotContextClient {

    private final InstitutionClient institutionClient;

    public EquipmentSlotContextResponse getSlotContext(
            UUID equipmentId,
            UUID examTypeId,
            LocalDate date
    ) {
        if (equipmentId == null || examTypeId == null || date == null) {
            throw new BaseException(ErrorCode.INVALID_INPUT);
        }

        ApiResponse<EquipmentSlotContextResponse> response;

        try {
            response = institutionClient.getSlotContext(
                    equipmentId,
                    examTypeId,
                    date
            );
        } catch (FeignException e) {
            throw convertException(e);
        }

        if (response == null
                || !Boolean.TRUE.equals(response.getSuccess())
                || response.getData() == null) {
            throw new BaseException(ErrorCode.REFERENCE_RESPONSE_INVALID);
        }

        EquipmentSlotContextResponse data = response.getData();

        if (!equipmentId.equals(data.equipmentId())
                || !examTypeId.equals(data.examTypeId())
                || !date.equals(data.date())
                || data.institutionId() == null
                || data.durationMinutes() == null
                || data.durationMinutes() < 1
                || data.operatingStartTime() == null
                || data.operatingEndTime() == null
                || !data.operatingStartTime().isBefore(
                data.operatingEndTime()
        )) {
            throw new BaseException(ErrorCode.REFERENCE_RESPONSE_INVALID);
        }

        return data;
    }

    private BaseException convertException(FeignException e) {
        if (e.status() == 400) {
            return new BaseException(ErrorCode.INVALID_INPUT);
        }

        if (e.status() == 404) {
            return new BaseException(ErrorCode.RESOURCE_NOT_FOUND);
        }

        if (e.status() == 409) {
            return new BaseException(ErrorCode.REFERENCE_NOT_AVAILABLE);
        }

        if (e.status() < 0 || e.status() >= 500) {
            return new BaseException(ErrorCode.REFERENCE_SERVICE_UNAVAILABLE);
        }

        return new BaseException(ErrorCode.REFERENCE_RESPONSE_INVALID);
    }
}