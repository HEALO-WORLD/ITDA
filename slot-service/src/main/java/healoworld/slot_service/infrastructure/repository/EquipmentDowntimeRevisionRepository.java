package healoworld.slot_service.infrastructure.repository;

import healoworld.slot_service.domain.entity.EquipmentDowntimeRevision;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EquipmentDowntimeRevisionRepository extends JpaRepository<EquipmentDowntimeRevision, UUID> {

}
