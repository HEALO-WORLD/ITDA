package healoworld.institution_service.application.service.equipment;

import healoworld.institution_service.domain.entity.Equipment;
import healoworld.institution_service.global.exception.BaseException;
import healoworld.institution_service.global.exception.ErrorCode;
import healoworld.institution_service.infrastructure.repository.EquipmentRepository;
import healoworld.institution_service.presentation.dto.response.EquipmentOwnerResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

// 장비 ID로 실제 소속 기관을 알려준다 (서비스 간 내부 호출 전용)
// Slot Service가 장비 중단 이벤트에 넣을 기관 ID를 요청자 정보가 아닌 장비 원본 데이터에서 가져오기 위해 쓴다
// 장비 운영 상태(OPERATIONAL·FAILED·MAINTENANCE)와 관계없이 조회한다. 고장 난 장비도 중단을 등록해야 하기 때문이다
@Service
@RequiredArgsConstructor
public class EquipmentOwnerService {

    private final EquipmentRepository equipmentRepository;

    @Transactional(readOnly = true)
    public EquipmentOwnerResponse getOwner(UUID equipmentId) {
        if (equipmentId == null) {
            throw new BaseException(ErrorCode.INVALID_INPUT);
        }

        Equipment equipment = equipmentRepository.findByIdAndDeletedAtIsNull(equipmentId)
                .orElseThrow(() -> new BaseException(ErrorCode.RESOURCE_NOT_FOUND));

        return EquipmentOwnerResponse.from(equipment);
    }
}
