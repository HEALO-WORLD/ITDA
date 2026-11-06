package healoworld.slot_service.infrastructure.repository;

import healoworld.slot_service.domain.entity.Slot;
import healoworld.slot_service.domain.enums.SlotStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Collection;
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
    // 장비 중단 구간에 걸린 슬롯(equipment_downtime_id 있음)은 확정하지 않는다.
    // 중단 등록이 같은 행을 먼저 잠그고 표시하면, 이 UPDATE는 잠금이 풀린 뒤 바뀐 행으로 조건을 다시 평가해 0행이 된다
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
           and s.equipmentDowntimeId is null
           and s.deletedAt is null
        """)
    int bookIfHeld(@Param("slotId") UUID slotId,
                   @Param("reservationId") UUID reservationId,
                   @Param("now") LocalDateTime now,
                   @Param("held") SlotStatus held,
                   @Param("booked") SlotStatus booked);

    // ReleaseHold: 같은 예약으로 HELD일 때만 해제한다
    // 장비 중단에 걸린 슬롯은 AVAILABLE로 열지 않는다 (releaseIntoUnavailable이 처리)
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
           and s.equipmentDowntimeId is null
        """)
    int releaseHoldIfHeld(@Param("slotId") UUID slotId,
                          @Param("reservationId") UUID reservationId,
                          @Param("now") LocalDateTime now,
                          @Param("held") SlotStatus held,
                          @Param("available") SlotStatus available);

    // ReleaseSlot: 같은 예약으로 BOOKED일 때만 해제한다
    // 장비 중단에 걸린 슬롯은 AVAILABLE로 열지 않는다 (releaseIntoUnavailable이 처리)
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        update Slot s
           set s.status = :available,
               s.reservationId = null,
               s.updatedAt = :now
         where s.id = :slotId
           and s.reservationId = :reservationId
           and s.status = :booked
           and s.equipmentDowntimeId is null
        """)
    int releaseBookedSlot(@Param("slotId") UUID slotId,
                          @Param("reservationId") UUID reservationId,
                          @Param("now") LocalDateTime now,
                          @Param("booked") SlotStatus booked,
                          @Param("available") SlotStatus available);

    // 장비 중단에 걸린 HELD·BOOKED 슬롯을 해당 예약이 반환할 때: AVAILABLE 대신 UNAVAILABLE로 바꾸고 예약 연결을 끊는다
    // ReleaseHold·ReleaseSlot·ADMIN 강제 해제에서 위 AVAILABLE 반환이 0행일 때 이어서 호출한다
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        update Slot s
           set s.status = :unavailable,
               s.reservationId = null,
               s.heldUntil = null,
               s.updatedAt = :now
         where s.id = :slotId
           and s.reservationId = :reservationId
           and s.status = :fromStatus
           and s.equipmentDowntimeId is not null
        """)
    int releaseIntoUnavailable(@Param("slotId") UUID slotId,
                               @Param("reservationId") UUID reservationId,
                               @Param("now") LocalDateTime now,
                               @Param("fromStatus") SlotStatus fromStatus,
                               @Param("unavailable") SlotStatus unavailable);

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
    // 장비 중단에 걸린 슬롯은 AVAILABLE로 열지 않는다 (expireBlockedHoldIfDue가 처리)
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
           and s.equipmentDowntimeId is null
        """)
    int expireHoldIfDue(@Param("slotId") UUID slotId,
                        @Param("reservationId") UUID reservationId,
                        @Param("now") LocalDateTime now,
                        @Param("held") SlotStatus held,
                        @Param("available") SlotStatus available);

    // 장비 중단에 걸린 HOLD가 만료되면 UNAVAILABLE로 바꾸고 예약 연결을 끊는다
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        update Slot s
           set s.status = :unavailable,
               s.reservationId = null,
               s.heldUntil = null,
               s.updatedAt = :now
         where s.id = :slotId
           and s.reservationId = :reservationId
           and s.status = :held
           and s.heldUntil <= :now
           and s.equipmentDowntimeId is not null
        """)
    int expireBlockedHoldIfDue(@Param("slotId") UUID slotId,
                               @Param("reservationId") UUID reservationId,
                               @Param("now") LocalDateTime now,
                               @Param("held") SlotStatus held,
                               @Param("unavailable") SlotStatus unavailable);

    // 장비 중단 구간 [from, to)와 겹치는 슬롯을 행 잠금(SELECT ... FOR UPDATE 계열)으로 조회한다
    // 슬롯은 하루 안에 있으므로 (date, startTime) < to 이고 (date, endTime) > from 이면 겹친다. 경계가 맞닿으면 제외된다
    // 잠금 순서를 일정하게 하려고 id 순으로 조회한다
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select s
          from Slot s
         where s.equipmentId = :equipmentId
           and s.deletedAt is null
           and (s.date < :toDate or (s.date = :toDate and s.startTime < :toTime))
           and (s.date > :fromDate or (s.date = :fromDate and s.endTime > :fromTime))
         order by s.id asc
        """)
    List<Slot> findOverlappingForUpdate(@Param("equipmentId") UUID equipmentId,
                                        @Param("fromDate") LocalDate fromDate,
                                        @Param("fromTime") LocalTime fromTime,
                                        @Param("toDate") LocalDate toDate,
                                        @Param("toTime") LocalTime toTime);

    // 잠근 슬롯 중 AVAILABLE만 UNAVAILABLE로 바꾸고 중단 ID를 기록한다 (상태 이력 대상)
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        update Slot s
           set s.status = :unavailable,
               s.equipmentDowntimeId = :downtimeId,
               s.updatedAt = :now
         where s.id in :slotIds
           and s.status = :available
        """)
    int blockAvailable(@Param("slotIds") Collection<UUID> slotIds,
                       @Param("downtimeId") UUID downtimeId,
                       @Param("now") LocalDateTime now,
                       @Param("available") SlotStatus available,
                       @Param("unavailable") SlotStatus unavailable);

    // 잠근 슬롯 중 HELD·BOOKED는 상태와 reservation_id를 그대로 두고 중단 ID만 기록한다
    // 이 표시가 있으면 확정(bookIfHeld)이 막히고, 해제·만료 시 AVAILABLE이 아닌 UNAVAILABLE로 바뀐다
    // 다른 중단이 이미 표시한 슬롯은 처음 표시한 중단 ID를 유지한다
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        update Slot s
           set s.equipmentDowntimeId = :downtimeId,
               s.updatedAt = :now
         where s.id in :slotIds
           and s.status in :occupiedStatuses
           and s.equipmentDowntimeId is null
        """)
    int markOccupiedAsBlocked(@Param("slotIds") Collection<UUID> slotIds,
                              @Param("downtimeId") UUID downtimeId,
                              @Param("now") LocalDateTime now,
                              @Param("occupiedStatuses") Collection<SlotStatus> occupiedStatuses);
}
