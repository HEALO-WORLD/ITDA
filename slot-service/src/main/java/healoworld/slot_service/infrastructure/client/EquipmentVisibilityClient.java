package healoworld.slot_service.infrastructure.client;

import feign.FeignException;
import healoworld.slot_service.global.dto.ApiResponse;
import healoworld.slot_service.global.exception.BaseException;
import healoworld.slot_service.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class EquipmentVisibilityClient {

    private final InstitutionClient institutionClient;

    public Set<UUID> getVisibleEquipmentIds(
            UUID callerInstitutionId,
            String role,
            UUID institutionId,
            boolean onlyOperational
    ) {
        if (callerInstitutionId == null || role == null
                || !Set.of("REQUESTER", "EXAMINER", "ADMIN").contains(role)) {
            throw new BaseException(ErrorCode.INVALID_INPUT);
        }

        ApiResponse<Set<UUID>> response;

        try {
            response = institutionClient.getVisibleEquipmentIds(
                    callerInstitutionId,
                    role,
                    institutionId,
                    onlyOperational
            );
        } catch (FeignException e) {
            throw convertException(e);
        }

        if (response == null
                || !Boolean.TRUE.equals(response.getSuccess())
                || response.getData() == null) {
            throw new BaseException(ErrorCode.REFERENCE_RESPONSE_INVALID);
        }

        for (UUID id : response.getData()) {
            if (id == null) {
                throw new BaseException(ErrorCode.REFERENCE_RESPONSE_INVALID);
            }
        }

        return Set.copyOf(response.getData());
    }

    private BaseException convertException(FeignException e) {
        return switch (e.status()) {
            case 400 -> new BaseException(ErrorCode.INVALID_INPUT);
            case 403 -> new BaseException(ErrorCode.FORBIDDEN);
            case 404 -> new BaseException(ErrorCode.RESOURCE_NOT_FOUND);
            case 409 -> new BaseException(ErrorCode.REFERENCE_NOT_AVAILABLE);
            default -> new BaseException(
                    e.status() < 0 || e.status() >= 500
                            ? ErrorCode.REFERENCE_SERVICE_UNAVAILABLE
                            : ErrorCode.REFERENCE_RESPONSE_INVALID
            );
        };
    }
}