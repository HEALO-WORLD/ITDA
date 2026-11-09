package healoworld.referral_service.infrastructure.repository;

import healoworld.referral_service.domain.entity.Referral;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

// 장비 장애 후속 처리 전용 의뢰 조회 Repository
// 의뢰 담당 코드(ReferralRepository)를 수정하지 않기 위해 같은 Referral Entity에 대한 잠금 조회만 따로 둔다
// Repository를 상속하므로 save·delete 등은 노출하지 않는다. 잠금 조회한 Entity의 변경은 호출한 트랜잭션의 dirty checking으로 반영된다
public interface IncidentAffectedReferralRepository extends Repository<Referral, UUID> {

    // SELECT ... FOR UPDATE: 트랜잭션이 끝날 때까지 이 의뢰 행을 다른 잠금·UPDATE로부터 지킨다
    // 장애 처리의 잠금 순서는 의뢰 → 예약이다 (IncidentReservationRepository.findByIdForUpdate보다 먼저 호출)
    // 삭제된 의뢰는 Referral의 @SQLRestriction("deleted_at IS NULL")에 의해 조회되지 않는다
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Referral r where r.id = :id")
    Optional<Referral> findByIdForUpdate(@Param("id") UUID id);
}
