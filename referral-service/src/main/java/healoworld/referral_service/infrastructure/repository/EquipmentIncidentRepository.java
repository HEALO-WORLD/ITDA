package healoworld.referral_service.infrastructure.repository;

import healoworld.referral_service.domain.entity.EquipmentIncident;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EquipmentIncidentRepository extends JpaRepository<EquipmentIncident, UUID>,
        EquipmentIncidentRepositoryCustom {

    // 같은 Kafka 이벤트가 이미 사건으로 저장됐는지 확인 (중복 수신 시 기존 사건을 그대로 쓰기 위함)
    // 최종 중복 방지는 source_event_id UNIQUE 제약이 담당하고, 이 조회는 빠른 사전 확인용이다
    Optional<EquipmentIncident> findBySourceEventId(UUID sourceEventId);

    boolean existsBySourceEventId(UUID sourceEventId);

    // 장비별 장애 이력. 정렬·페이지 크기는 호출하는 쪽이 Pageable로 정한다
    // TODO: 기간·상태 필터, equipment_id 인덱스 추가 여부는 목록 API 확정 후 결정
    Page<EquipmentIncident> findByEquipmentId(UUID equipmentId, Pageable pageable);

    // 확정된 예약 시간과 겹치는 같은 장비의 장애 ID (SlotBooked 처리 직후 늦게 확정된 영향 예약을 찾기 위해 사용)
    // 겹침 조건은 IncidentReservationRepository.findAffected()와 같다 (양 끝 미포함)
    // TODO: 장애 종료·복구 정책이 정해지면 종료된 장애를 제외할지 결정
    @Query("""
            select i.id
              from EquipmentIncident i
             where i.equipmentId = :equipmentId
               and i.startAt < :endTime
               and i.endAt > :startTime
             order by i.startAt, i.id
            """)
    List<UUID> findOverlappingIds(@Param("equipmentId") UUID equipmentId,
                                  @Param("startTime") LocalDateTime startTime,
                                  @Param("endTime") LocalDateTime endTime);
}
