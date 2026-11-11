package healoworld.institution_service.application.service.equipment;

import healoworld.institution_service.application.event.EquipmentAvailabilityChangedEvent;
import healoworld.institution_service.domain.entity.EquipmentStatusHistory;
import healoworld.institution_service.domain.enums.EquipmentStatus;
import healoworld.institution_service.infrastructure.repository.EquipmentRepository;
import healoworld.institution_service.infrastructure.repository.EquipmentStatusHistoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class EquipmentAvailabilityService {
    private final EquipmentRepository equipmentRepository;
    private final EquipmentStatusHistoryRepository historyRepository;

    @Transactional
    public void apply(EquipmentAvailabilityChangedEvent event) {
        var equipment = equipmentRepository.findForUpdateById(event.equipmentId())
                .orElseThrow(() -> new IllegalStateException("상태 이벤트의 장비를 찾을 수 없습니다."));
        if (!equipment.getInstitutionId().equals(event.institutionId())) {
            throw new IllegalArgumentException("장비의 소속 기관이 이벤트와 다릅니다.");
        }
        EquipmentStatus before = equipment.getStatus();
        if (!equipment.applyDowntimeStatus(event.revision(), EquipmentStatus.valueOf(event.status()))) {
            return; // 중복·역순 전달은 상태와 이력을 바꾸지 않는다.
        }
        if (before != equipment.getStatus()) {
            historyRepository.save(EquipmentStatusHistory.create(equipment.getId(), before, equipment.getStatus(),
                    event.reason(), event.changedBy(), event.occurredAt()));
        }
    }
}
