package healoworld.institution_service.infrastructure.repository;

import healoworld.institution_service.domain.entity.EquipmentOperatingHours;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EquipmentOperatingHoursRepository extends JpaRepository<EquipmentOperatingHours, UUID> {

    Optional<EquipmentOperatingHours> findByEquipmentIdAndWeek(UUID equipmentId, Integer week);

    List<EquipmentOperatingHours> findAllByEquipmentIdAndDeletedAtIsNullOrderByWeekAsc(UUID equipmentId);

    List<EquipmentOperatingHours> findAllByEquipmentIdOrderByWeekAsc(UUID equipmentId);

    Optional<EquipmentOperatingHours> findByEquipmentIdAndWeekAndDeletedAtIsNull(UUID equipmentId, Integer week);
}