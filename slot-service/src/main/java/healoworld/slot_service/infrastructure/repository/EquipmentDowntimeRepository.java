package healoworld.slot_service.infrastructure.repository;

import healoworld.slot_service.domain.entity.EquipmentDowntime;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.UUID;

public interface EquipmentDowntimeRepository extends JpaRepository<EquipmentDowntime, UUID> {

    // [from, to) 구간과 겹치는 삭제되지 않은 중단 이력이 있는지 확인한다
    // 경계가 맞닿기만 하면(중단 종료 = 슬롯 시작) 겹치지 않는 것으로 본다: start < to && end > from
    // 다음 단계에서 신규 HOLD·최종 확정 재검증과 중단 구간 슬롯 차단에 사용한다
    @Query("""
        select count(d) > 0
          from EquipmentDowntime d
         where d.equipmentId = :equipmentId
           and d.deletedAt is null
           and d.startedAt < :to
           and d.endedAt > :from
        """)
    boolean existsActiveOverlap(@Param("equipmentId") UUID equipmentId,
                                @Param("from") LocalDateTime from,
                                @Param("to") LocalDateTime to);
}
