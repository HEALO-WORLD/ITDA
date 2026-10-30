package healoworld.referral_service.infrastructure.repository;

import healoworld.referral_service.domain.entity.Reservation;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

// 장비 장애 기능 전용 예약 조회 Repository (읽기 전용)
// 예약 담당 코드(ReservationRepository)를 수정하지 않기 위해 같은 Reservation Entity에 대한 조회만 따로 둔다
// Repository를 상속하므로 save·delete 등 변경 메서드는 노출하지 않는다
public interface IncidentReservationRepository extends Repository<Reservation, UUID> {

    // 장비 장애의 영향을 받는 확정 예약: 같은 장비 + CONFIRMED + 예약 시간이 장애 영향 구간과 겹침
    // 겹침 조건은 양 끝을 포함하지 않는다 (예약 종료 = 장애 시작, 예약 시작 = 장애 종료이면 영향 없음)
    // 삭제된 예약은 Reservation의 @SQLRestriction("deleted_at IS NULL")에 의해 자동으로 제외된다
    // 의뢰 ID는 예약이 실제로 연결된 p_reservations.referral_id 값이다 (p_referrals를 조인하지 않는다)
    @Query("""
            select new healoworld.referral_service.infrastructure.repository.AffectedReservation(r.id, r.referral.id)
              from Reservation r
             where r.equipmentId = :equipmentId
               and r.reservationStatus = healoworld.referral_service.domain.enums.ReservationStatus.CONFIRMED
               and r.startTime < :endAt
               and r.endTime > :startAt
             order by r.startTime, r.id
            """)
    List<AffectedReservation> findAffected(@Param("equipmentId") UUID equipmentId,
                                           @Param("startAt") LocalDateTime startAt,
                                           @Param("endAt") LocalDateTime endAt);
}
