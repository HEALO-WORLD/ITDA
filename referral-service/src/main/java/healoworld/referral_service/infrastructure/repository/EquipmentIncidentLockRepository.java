package healoworld.referral_service.infrastructure.repository;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

// 장비 단위 PostgreSQL advisory lock (트랜잭션이 끝나면 자동 해제)
// 장애 영향 예약 조회(createPendingReferrals)와 예약 확정(SlotBooked) 직후의 장애 확인이 같은 장비에서 동시에 실행될 때
// 두 작업을 순서대로 실행시켜, 서로 상대의 커밋 전 변경을 보지 못해 영향 예약을 둘 다 놓치는 상황을 막는다
@Repository
@RequiredArgsConstructor
public class EquipmentIncidentLockRepository {

    // 다른 용도의 advisory lock과 키가 겹치지 않도록 첫 번째 키를 이 기능 전용 값으로 고정한다 ("INCD")
    private static final int NAMESPACE = 0x494E4344;

    private final EntityManager entityManager;

    @Transactional(propagation = Propagation.MANDATORY)
    public void lock(UUID equipmentId) {
        entityManager.createNativeQuery("select 1 from pg_advisory_xact_lock(:namespace, :equipmentKey)")
                .setParameter("namespace", NAMESPACE)
                .setParameter("equipmentKey", equipmentId.hashCode())
                .getSingleResult();
    }
}
