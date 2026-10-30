package healoworld.slot_service.infrastructure.repository;

import healoworld.slot_service.domain.entity.Slot;
import healoworld.slot_service.domain.enums.SlotStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Set;
import java.util.UUID;

public interface SlotRepository extends JpaRepository<Slot, UUID> {
    @Query("""
        select count(s) > 0
        from Slot s
        where s.equipmentId = :equipmentId
        and s.date = :date
        and s.startTime < :endTime
        and s.endTime > :startTime
        """)
    boolean existsOverlappingSlot(
            @Param("equipmentId") UUID equipmentId,
            @Param("date") LocalDate date,
            @Param("startTime") LocalTime startTime,
            @Param("endTime") LocalTime endTime
    );

    @Query("""
        select s
        from Slot s
        where (
            s.date > :fromDate
            or (s.date = :fromDate and s.startTime >= :fromTime)
        )
        and (
            s.date < :toDate
            or (s.date = :toDate and s.startTime < :toTime)
        )
        and s.equipmentId = coalesce(:equipmentId, s.equipmentId)
        and s.examTypeId = coalesce(:examTypeId, s.examTypeId)
        and s.status = coalesce(:status, s.status)
        and s.equipmentId in :visibleEquipmentIds
        order by s.date asc, s.startTime asc, s.id asc
        """)
    Slice<Slot> searchSlots(
            @Param("fromDate") LocalDate fromDate,
            @Param("fromTime") LocalTime fromTime,
            @Param("toDate") LocalDate toDate,
            @Param("toTime") LocalTime toTime,
            @Param("equipmentId") UUID equipmentId,
            @Param("examTypeId") UUID examTypeId,
            @Param("status") SlotStatus status,
            @Param("visibleEquipmentIds") Set<UUID> visibleEquipmentIds,
            Pageable pageable
    );
}
