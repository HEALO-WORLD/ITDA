package healoworld.referral_service.infrastructure.repository;

import healoworld.referral_service.domain.entity.EquipmentIncident;
import healoworld.referral_service.domain.enums.EquipmentIncidentStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface EquipmentIncidentRepositoryCustom {

    // 장애 목록: 값이 있는 조건만 적용하고(null이면 조건 없음), 최신 발생순으로 정렬한다
    Page<EquipmentIncident> search(UUID institutionId, UUID equipmentId, EquipmentIncidentStatus status,
                                   Pageable pageable);
}
