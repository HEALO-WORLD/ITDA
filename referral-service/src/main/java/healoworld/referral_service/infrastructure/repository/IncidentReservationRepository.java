package healoworld.referral_service.infrastructure.repository;

import healoworld.referral_service.domain.entity.Reservation;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

// 장비 장애 기능 전용 예약 조회 Repository
// 예약 담당 코드(ReservationRepository)를 수정하지 않기 위해 같은 Reservation Entity에 대한 조회만 따로 둔다
// Repository를 상속하므로 save·delete 등은 노출하지 않는다. 잠금 조회한 Entity의 변경은 호출한 트랜잭션의 dirty checking으로 반영된다
public interface IncidentReservationRepository extends Repository<Reservation, UUID> {

    // SELECT ... FOR UPDATE: 트랜잭션이 끝날 때까지 이 예약 행을 다른 잠금·UPDATE로부터 지킨다
    // 장애 처리의 잠금 순서는 의뢰 → 예약이다 (IncidentAffectedReferralRepository.findByIdForUpdate 다음에 호출)
    // 삭제된 예약은 Reservation의 @SQLRestriction("deleted_at IS NULL")에 의해 조회되지 않는다
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Reservation r where r.id = :id")
    Optional<Reservation> findByIdForUpdate(@Param("id") UUID id);

    // 장비 장애의 영향을 받는 확정 예약: 같은 장비 + 예약 시간이 장애 영향 구간과 겹침 + 아래 두 상태 중 하나
    //  1) CONFIRMED
    //  2) 이 장애 이벤트보다 먼저 처리된 SlotsBlocked(정합성 복구 등)로 이미 차단된 확정 예약
    //     BLOCKED + EQUIPMENT_FAILURE + 확정 시각 있음 + 의뢰가 이 예약을 현재 예약으로 둔 채 NEEDS_RESCHEDULE
    //     + 의뢰의 검사가 시작·완료되지 않음
    //     이 예약은 장애 처리 전에 CONFIRMED가 아니게 돼 빠지면 후속 작업과 재조정 알림이 만들어지지 않는다.
    //     작업은 IncidentProcessingService가 ALREADY_BLOCKED로 끝내며 상태·이력은 바꾸지 않고 누락된 알림만 보완한다
    //     확정 전(REQUESTED·HELD)에 차단된 예약, 이미 다른 예약으로 재예약 중이거나 취소된 의뢰는 대상이 아니다
    // 겹침 조건은 양 끝을 포함하지 않는다 (예약 종료 = 장애 시작, 예약 시작 = 장애 종료이면 영향 없음)
    // 삭제된 예약은 Reservation의 @SQLRestriction("deleted_at IS NULL")에 의해 자동으로 제외된다
    // 의뢰 ID는 예약이 실제로 연결된 p_reservations.referral_id 값이다
    // 의뢰·검사 조건은 차단된 예약에만 EXISTS로 확인한다 (CONFIRMED 예약의 조회 조건과 결과는 기존과 같다)
    // 잠금 없는 조회라 그 뒤 상태가 바뀔 수 있지만, 작업 처리 시 의뢰 → 예약을 잠근 뒤 다시 판단한다
    @Query("""
            select new healoworld.referral_service.infrastructure.repository.AffectedReservation(r.id, r.referral.id)
              from Reservation r
             where r.equipmentId = :equipmentId
               and r.startTime < :endAt
               and r.endTime > :startAt
               and (r.reservationStatus = healoworld.referral_service.domain.enums.ReservationStatus.CONFIRMED
                    or (r.reservationStatus = healoworld.referral_service.domain.enums.ReservationStatus.BLOCKED
                        and r.failureReason = healoworld.referral_service.domain.enums.FailureReason.EQUIPMENT_FAILURE
                        and r.confirmedAt is not null
                        and exists (select 1 from Referral f
                                     where f.id = r.referral.id
                                       and f.currentReservationId = r.id
                                       and f.referralStatus = healoworld.referral_service.domain.enums.ReferralStatus.NEEDS_RESCHEDULE)
                        and not exists (select 1 from Examination e
                                         where e.referralId = r.referral.id
                                           and e.status in (healoworld.referral_service.domain.enums.ExaminationStatus.IN_PROGRESS,
                                                            healoworld.referral_service.domain.enums.ExaminationStatus.COMPLETED))))
             order by r.startTime, r.id
            """)
    List<AffectedReservation> findAffected(@Param("equipmentId") UUID equipmentId,
                                           @Param("startAt") LocalDateTime startAt,
                                           @Param("endAt") LocalDateTime endAt);
}
