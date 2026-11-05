package healoworld.slot_service.infrastructure.client;

import feign.FeignException;
import healoworld.slot_service.global.dto.ApiResponse;
import healoworld.slot_service.global.exception.BaseException;
import healoworld.slot_service.global.exception.ErrorCode;
import healoworld.slot_service.infrastructure.client.dto.EquipmentOwnerResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.UUID;

// 장비의 실제 소속 기관 ID를 Institution Service에서 조회한다
// 장비 중단 이벤트의 institutionId는 요청자 헤더나 요청 본문이 아닌 이 값만 사용한다.
// 응답이 비었거나 다른 장비의 응답이면 예외를 던져, 잘못된 기관 ID로 이벤트가 저장·발행되지 않게 한다
@Component
@RequiredArgsConstructor
public class EquipmentOwnerClient {

    private final InstitutionClient institutionClient;

    public UUID getOwnerInstitutionId(UUID equipmentId) {
        if (equipmentId == null) {
            throw new BaseException(ErrorCode.INVALID_INPUT);
        }

        ApiResponse<EquipmentOwnerResponse> response;
        try {
            response = institutionClient.getEquipmentOwner(equipmentId);
        } catch (FeignException e) {
            throw convertException(e);
        }

        if (response == null
                || !Boolean.TRUE.equals(response.getSuccess())
                || response.getData() == null
                || !equipmentId.equals(response.getData().equipmentId())
                || response.getData().institutionId() == null) {
            throw new BaseException(ErrorCode.REFERENCE_RESPONSE_INVALID);
        }

        return response.getData().institutionId();
    }

    private BaseException convertException(FeignException e) {
        return switch (e.status()) {
            case 400 -> new BaseException(ErrorCode.INVALID_INPUT);
            case 404 -> new BaseException(ErrorCode.RESOURCE_NOT_FOUND);
            default -> new BaseException(
                    e.status() < 0 || e.status() >= 500
                            ? ErrorCode.REFERENCE_SERVICE_UNAVAILABLE
                            : ErrorCode.REFERENCE_RESPONSE_INVALID
            );
        };
    }
}
