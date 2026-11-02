package healoworld.institution_service.infrastructure.repository;

import healoworld.institution_service.domain.entity.Equipment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface EquipmentRepository extends JpaRepository<Equipment, UUID> {

}