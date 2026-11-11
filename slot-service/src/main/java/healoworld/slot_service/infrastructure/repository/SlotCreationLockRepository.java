package healoworld.slot_service.infrastructure.repository;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class SlotCreationLockRepository {

    private final EntityManager entityManager;

    @Transactional(propagation = Propagation.MANDATORY)
    public void lockEquipmentShared(UUID equipmentId) {
        entityManager.createNativeQuery(
                """
                select 1 
                from pg_advisory_xact_lock_shared(:key)
                """)
                .setParameter("key", equipmentId.getMostSignificantBits() ^ equipmentId.getLeastSignificantBits())
                .getSingleResult();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void lockEquipment(UUID equipmentId) {
        // bigint advisory namespace는 기존 (int,int) namespace와 독립적이다.
        entityManager.createNativeQuery(
                """
                select 1
                from pg_advisory_xact_lock(:key)
                """)
                .setParameter("key", equipmentId.getMostSignificantBits() ^ equipmentId.getLeastSignificantBits())
                .getSingleResult();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void lock(UUID equipmentId, LocalDate date) {
        entityManager.createNativeQuery(
                """
                select 1
                from pg_advisory_xact_lock(:equipmentKey, :dateKey)
                """)
                .setParameter("equipmentKey", equipmentId.hashCode())
                .setParameter("dateKey", date.hashCode())
                .getSingleResult();
    }
}