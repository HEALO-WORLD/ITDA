package healoworld.institution_service.domain.entity;

import healoworld.institution_service.global.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.Objects;
import java.util.UUID;

@Entity
@Getter
@Table(name = "p_equipment_operating_hours", uniqueConstraints = @UniqueConstraint(name = "uk_equipment_week", columnNames = {"equipment_id", "week"}))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EquipmentOperatingHours extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "equipment_id", nullable = false, updatable = false)
    private UUID equipmentId;

    @Column(name = "week", nullable = false, updatable = false)
    private Integer week;

    @Column(name = "start_time", nullable = false)
    private LocalTime startTime;

    @Column(name = "end_time", nullable = false)
    private LocalTime endTime;

    private EquipmentOperatingHours(
            Equipment equipment,
            DayOfWeek dayOfWeek,
            LocalTime startTime,
            LocalTime endTime
    ) {
        Objects.requireNonNull(equipment, "장비는 필수입니다.");
        Objects.requireNonNull(dayOfWeek, "요일은 필수입니다.");

        if (equipment.getId() == null || equipment.getDeletedAt() != null) {
            throw new IllegalArgumentException("저장된 미삭제 장비에만 운영시간을 설정할 수 있습니다.");
        }

        validateTimes(startTime, endTime);

        this.equipmentId = equipment.getId();
        this.week = dayOfWeek.getValue();
        this.startTime = startTime;
        this.endTime = endTime;
    }

    public static EquipmentOperatingHours create(
            Equipment equipment,
            DayOfWeek dayOfWeek,
            LocalTime startTime,
            LocalTime endTime
    ) {
        return new EquipmentOperatingHours(equipment, dayOfWeek, startTime, endTime);
    }

    public void changeTimes(LocalTime startTime, LocalTime endTime) {
        validateTimes(startTime, endTime);

        this.startTime = startTime;
        this.endTime = endTime;
    }

    public void setOperatingTimes(LocalTime startTime, LocalTime endTime) {
        changeTimes(startTime, endTime);
        restore();
    }

    public void close(UUID userId) {
        softDelete(Objects.requireNonNull(userId, "변경자 ID는 필수입니다."));
    }

    private void validateTimes(LocalTime startTime, LocalTime endTime) {
        if (startTime == null
                || endTime == null
                || !startTime.isBefore(endTime)) {
            throw new IllegalArgumentException("시작·종료 시간을 함께 입력하고 시작은 종료보다 빨라야 합니다.");
        }
    }
}
