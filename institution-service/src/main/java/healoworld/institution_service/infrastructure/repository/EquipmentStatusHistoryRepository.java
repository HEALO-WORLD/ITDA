package healoworld.institution_service.infrastructure.repository;

import healoworld.institution_service.domain.entity.EquipmentStatusHistory;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EquipmentStatusHistoryRepository extends JpaRepository<EquipmentStatusHistory, UUID> {
    Page<EquipmentStatusHistory> findByEquipmentIdOrderByChangedAtDescIdDesc(UUID equipmentId, Pageable pageable);
}
