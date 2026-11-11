package healoworld.slot_service.infrastructure.repository;

import healoworld.slot_service.domain.entity.EquipmentDowntime;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;

public interface EquipmentDowntimeRepository extends JpaRepository<EquipmentDowntime, UUID> {

    // [from, to) 구간과 겹치는 삭제되지 않은 중단 이력이 있는지 확인한다
    // 경계가 맞닿기만 하면(중단 종료 = 슬롯 시작) 겹치지 않는 것으로 본다: start < to && end > from
    // 다음 단계에서 신규 HOLD·최종 확정 재검증과 중단 구간 슬롯 차단에 사용한다
    @Query("""
        select count(d) > 0
          from EquipmentDowntime d
         where d.equipmentId = :equipmentId
           and d.deletedAt is null
           and d.recoveredAt is null
           and d.startedAt < :to
           and d.endedAt > :from
        """)
    boolean existsActiveOverlap(@Param("equipmentId") UUID equipmentId,
                                @Param("from") LocalDateTime from,
                                @Param("to") LocalDateTime to);

    Optional<EquipmentDowntime> findByIdAndDeletedAtIsNull(UUID id);

    List<EquipmentDowntime> findByEquipmentIdAndDeletedAtIsNullAndRecoveredAtIsNull(UUID equipmentId);

    @Query("""
        select distinct d.equipmentId from EquipmentDowntime d
        where d.deletedAt is null
        and d.recoveredAt is null
        and d.activatedAt is null
        and d.startedAt <= :now
        and d.registeredBy is not null
        order by d.equipmentId
        """)
    List<UUID> findDueEquipmentIds(@Param("now") LocalDateTime now, Pageable pageable);

    // 예정 기간과의 겹침 또는 이미 시작했지만 복구되지 않은 장비인지 확인한다.
    // 예정 종료 시각은 실제 복구 확인을 대신하지 않는다.
    @Query("""
        select count(d) > 0 from EquipmentDowntime d
        where d.equipmentId = :equipmentId
        and d.deletedAt is null
        and d.recoveredAt is null
        and d.startedAt < :to
        and (d.endedAt > :from or d.startedAt <= :now)
        """)
    boolean existsBlockingOverlap(@Param("equipmentId") UUID equipmentId,
                                  @Param("from") LocalDateTime from, @Param("to") LocalDateTime to,
                                  @Param("now") LocalDateTime now);
}
