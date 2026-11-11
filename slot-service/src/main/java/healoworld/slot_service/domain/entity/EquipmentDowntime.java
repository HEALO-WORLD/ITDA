package healoworld.slot_service.domain.entity;

import healoworld.slot_service.global.entity.BaseEntity;
import healoworld.slot_service.domain.enums.EquipmentDowntimeType;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

// 장비 중단 이력 (p_equipment_downtimes)
// 신규 HOLD와 최종 확정(BookSlot) 시 이 테이블로 장비 중단 여부를 다시 검증한다
// 시간은 한국 시간(KST) 기준 LocalDateTime으로 저장한다 (TIMESTAMP, 시간대 없음)
@Entity
// 장비별 중단 구간 겹침 조회(existsActiveOverlap)에 사용한다
@Table(name = "p_equipment_downtimes",
        indexes = @Index(name = "idx_downtime_equipment_period", columnList = "equipment_id, started_at, ended_at"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EquipmentDowntime extends BaseEntity {

    public static final int REASON_MAX_LENGTH = 500;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    // Institution Service의 p_equipments.id를 UUID로 참조 (서비스 간 FK 없음)
    @Column(name = "equipment_id", nullable = false)
    private UUID equipmentId;

    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    @Column(name = "ended_at", nullable = false)
    private LocalDateTime endedAt;

    @Column(name = "reason", length = REASON_MAX_LENGTH)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(name = "downtime_type", nullable = false, length = 20)
    private EquipmentDowntimeType downtimeType;

    @Column(name = "institution_id")
    private UUID institutionId;

    @Column(name = "registered_by")
    private UUID registeredBy;

    @Column(name = "activated_at")
    private LocalDateTime activatedAt;

    @Column(name = "recovered_at")
    private LocalDateTime recoveredAt;

    @Column(name = "recovered_by")
    private UUID recoveredBy;

    @Column(name = "recovery_reason", length = REASON_MAX_LENGTH)
    private String recoveryReason;

    public static EquipmentDowntime register(
            UUID equipmentId,
            UUID institutionId,
            EquipmentDowntimeType downtimeType,
            LocalDateTime startedAt,
            LocalDateTime endedAt,
            String reason,
            UUID registeredBy
    ) {
        EquipmentDowntime downtime = new EquipmentDowntime(equipmentId, startedAt, endedAt, reason);
        downtime.institutionId = Objects.requireNonNull(institutionId);
        downtime.downtimeType = Objects.requireNonNull(downtimeType);
        downtime.registeredBy = Objects.requireNonNull(registeredBy);
        return downtime;
    }

    public boolean activate(LocalDateTime now) {
        // 서버가 예정 종료 이후 재시작되어도 복구 확인이 없으면 활성화한다.
        if (recoveredAt != null || activatedAt != null || startedAt.isAfter(now)) {
            return false;
        }
        activatedAt = now;
        return true;
    }

    public boolean recover(LocalDateTime now, UUID userId, String reason) {
        if (recoveredAt != null) {
            return false;
        }
        if (startedAt.isAfter(now)) {
            throw new IllegalStateException("시작 전 일정은 복구 확인할 수 없습니다.");
        }
        if (reason == null || reason.isBlank() || reason.length() > REASON_MAX_LENGTH) {
            throw new IllegalArgumentException("복구 확인 사유는 1~500자여야 합니다.");
        }
        recoveredAt = now;
        recoveredBy = Objects.requireNonNull(userId);
        recoveryReason = reason.strip();
        return true;
    }

    public void extend(LocalDateTime newEnd, LocalDateTime now) {
        if (recoveredAt != null || !newEnd.isAfter(endedAt) || !newEnd.isAfter(now)) {
            throw new IllegalStateException("미복구 중단의 종료 예정 시각만 현재 이후로 연장할 수 있습니다.");
        }
        endedAt = newEnd;
    }

    private EquipmentDowntime(UUID equipmentId, LocalDateTime startedAt, LocalDateTime endedAt, String reason) {
        this.equipmentId = Objects.requireNonNull(equipmentId, "장비 ID는 필수입니다.");
        Objects.requireNonNull(startedAt, "중단 시작 일시는 필수입니다.");
        Objects.requireNonNull(endedAt, "중단 종료 일시는 필수입니다.");

        if (!startedAt.isBefore(endedAt)) {
            throw new IllegalArgumentException("중단 시작 일시는 종료 일시보다 빨라야 합니다.");
        }

        this.startedAt = startedAt;
        this.endedAt = endedAt;
        this.reason = reason;
        this.downtimeType = EquipmentDowntimeType.FAILURE;
    }

    public static EquipmentDowntime create(UUID equipmentId, LocalDateTime startedAt, LocalDateTime endedAt,
                                           String reason) {
        return new EquipmentDowntime(equipmentId, startedAt, endedAt, reason);
    }
}
