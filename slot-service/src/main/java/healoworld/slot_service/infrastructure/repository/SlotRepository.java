package healoworld.slot_service.infrastructure.repository;

import healoworld.slot_service.domain.entity.Slot;
import healoworld.slot_service.domain.enums.SlotStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface SlotRepository extends JpaRepository<Slot, UUID> {

    Optional<Slot> findByIdAndDeletedAtIsNull(UUID id);

    @Query("""
        select count(s) > 0
        from Slot s
        where s.equipmentId = :equipmentId
        and s.deletedAt is null
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
        where s.deletedAt is null
        and (
            s.date > :fromDate
            or (s.date = :fromDate and s.startTime >= :fromTime)
        )
        and (
            s.date < :toDate
            or (s.date = :toDate and s.startTime < :toTime)
        )
        and (
            :onlyFuture = false
            or s.date > :today
            or (s.date = :today and s.startTime > :nowTime)
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
            @Param("onlyFuture") boolean onlyFuture,
            @Param("today") LocalDate today,
            @Param("nowTime") LocalTime nowTime,
            Pageable pageable
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        update Slot s
           set s.status = :held,
               s.reservationId = :reservationId,
               s.heldUntil = :heldUntil,
               s.updatedAt = :now
         where s.id = :slotId
           and s.status = :available
           and s.deletedAt is null
        """)
    int holdIfAvailable(@Param("slotId") UUID slotId,
                        @Param("reservationId") UUID reservationId,
                        @Param("heldUntil") LocalDateTime heldUntil,
                        @Param("now") LocalDateTime now,
                        @Param("held") SlotStatus held,
                        @Param("available") SlotStatus available);

    // 같은 예약으로 HELD이고 아직 만료 전(held_until > now)일 때만 BOOKED로 확정한다.
    // 만료 스캐너(expireHoldIfDue, held_until <= now)와 조건이 겹치지 않아 둘 중 하나만 성공한다
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        update Slot s
           set s.status = :booked,
               s.heldUntil = null,
               s.updatedAt = :now
         where s.id = :slotId
           and s.reservationId = :reservationId
           and s.status = :held
           and s.heldUntil > :now
           and s.deletedAt is null
        """)
    int bookIfHeld(@Param("slotId") UUID slotId,
                   @Param("reservationId") UUID reservationId,
                   @Param("now") LocalDateTime now,
                   @Param("held") SlotStatus held,
                   @Param("booked") SlotStatus booked);

    // ReleaseHold: 같은 예약으로 HELD일 때만 해제한다
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        update Slot s
           set s.status = :available,
               s.reservationId = null,
               s.heldUntil = null,
               s.updatedAt = :now
         where s.id = :slotId
           and s.reservationId = :reservationId
           and s.status = :held
        """)
    int releaseHoldIfHeld(@Param("slotId") UUID slotId,
                          @Param("reservationId") UUID reservationId,
                          @Param("now") LocalDateTime now,
                          @Param("held") SlotStatus held,
                          @Param("available") SlotStatus available);

    // ReleaseSlot: 같은 예약으로 BOOKED일 때만 해제한다
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        update Slot s
           set s.status = :available,
               s.reservationId = null,
               s.updatedAt = :now
         where s.id = :slotId
           and s.reservationId = :reservationId
           and s.status = :booked
        """)
    int releaseBookedSlot(@Param("slotId") UUID slotId,
                          @Param("reservationId") UUID reservationId,
                          @Param("now") LocalDateTime now,
                          @Param("booked") SlotStatus booked,
                          @Param("available") SlotStatus available);

    // ReleaseSlot: 장애로 UNAVAILABLE인 슬롯은 상태를 유지하고 예약 연결만 끊는다
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        update Slot s
           set s.reservationId = null,
               s.updatedAt = :now
         where s.id = :slotId
           and s.reservationId = :reservationId
           and s.status = :unavailable
        """)
    int detachReservationIfUnavailable(@Param("slotId") UUID slotId,
                                       @Param("reservationId") UUID reservationId,
                                       @Param("now") LocalDateTime now,
                                       @Param("unavailable") SlotStatus unavailable);

    // HOLD 만료 대상 조회. 오래된 HOLD부터 처리한다
    @Query("""
        select s
          from Slot s
         where s.status = :held
           and s.heldUntil <= :now
           and s.deletedAt is null
         order by s.heldUntil asc
        """)
    List<Slot> findExpiredHolds(@Param("held") SlotStatus held,
                                @Param("now") LocalDateTime now,
                                Pageable pageable);

    // 아직 같은 예약으로 HELD이고 만료 시각이 지난 경우에만 AVAILABLE로 되돌린다.
    // 조회 후 그 사이 확정되거나 다른 예약으로 다시 HOLD된 슬롯은 0행이 반환된다
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        update Slot s
           set s.status = :available,
               s.reservationId = null,
               s.heldUntil = null,
               s.updatedAt = :now
         where s.id = :slotId
           and s.reservationId = :reservationId
           and s.status = :held
           and s.heldUntil <= :now
        """)
    int expireHoldIfDue(@Param("slotId") UUID slotId,
                        @Param("reservationId") UUID reservationId,
                        @Param("now") LocalDateTime now,
                        @Param("held") SlotStatus held,
                        @Param("available") SlotStatus available);
}
