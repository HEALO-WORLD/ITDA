package healoworld.slot_service.domain.entity;

import healoworld.slot_service.domain.enums.SlotStatus;
import healoworld.slot_service.global.exception.BaseException;
import healoworld.slot_service.global.exception.ErrorCode;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "p_slots")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Slot {

    public static final Duration HOLD_DURATION = Duration.ofMinutes(5);

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "equipment_id", nullable = false)
    private UUID equipmentId;

    @Column(name = "exam_type_id", nullable = false)
    private UUID examTypeId;

    @Column(name = "equipment_downtime_id")
    private UUID equipmentDowntimeId;

    // Referral Service의 p_reservations.id를 UUID로 참조
    @Column(name = "reservation_id")
    private UUID reservationId;

    @Column(name = "date", nullable = false)
    private LocalDate date;

    @Column(name = "start_time", nullable = false)
    private LocalTime startTime;

    @Column(name = "end_time", nullable = false)
    private LocalTime endTime;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private SlotStatus status;

    @Column(name = "held_until")
    private LocalDateTime heldUntil;

    private Slot(
            UUID equipmentId,
            UUID examTypeId,
            LocalDate date,
            LocalTime startTime,
            LocalTime endTime
    ) {
        this.equipmentId = Objects.requireNonNull(equipmentId, "장비 ID는 필수입니다.");
        this.examTypeId = Objects.requireNonNull(examTypeId, "검사 유형 ID는 필수입니다.");
        this.date = Objects.requireNonNull(date, "검사 날짜는 필수입니다.");

        validateTimeRange(startTime, endTime);

        this.startTime = startTime;
        this.endTime = endTime;
        this.status = SlotStatus.AVAILABLE;
    }

    public static Slot create(
            UUID equipmentId,
            UUID examTypeId,
            LocalDate date,
            LocalTime startTime,
            LocalTime endTime
    ) {
        return new Slot(
                equipmentId,
                examTypeId,
                date,
                startTime,
                endTime
        );
    }

    private void validateTimeRange(LocalTime startTime, LocalTime endTime) {
        Objects.requireNonNull(startTime, "시작 시간은 필수입니다.");
        Objects.requireNonNull(endTime, "종료 시간은 필수입니다.");

        if (!startTime.isBefore(endTime)) {
            throw new IllegalArgumentException("시작 시간은 종료 시간보다 빨라야 합니다.");
        }
    }

    public boolean isHoldable() {
        return this.status == SlotStatus.AVAILABLE;
    }

    public void hold(UUID reservationId, LocalDateTime now) {
        if (this.status != SlotStatus.AVAILABLE) {
            throw new BaseException(ErrorCode.SLOT_NOT_AVAILABLE);
        }
        this.status = SlotStatus.HELD;
        this.reservationId = reservationId;
        this.heldUntil = now.plus(HOLD_DURATION);
    }
}
