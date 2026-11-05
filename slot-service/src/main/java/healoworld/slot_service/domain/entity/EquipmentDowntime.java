package healoworld.slot_service.domain.entity;

import healoworld.slot_service.global.entity.BaseEntity;
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
    }

    public static EquipmentDowntime create(UUID equipmentId, LocalDateTime startedAt, LocalDateTime endedAt,
                                           String reason) {
        return new EquipmentDowntime(equipmentId, startedAt, endedAt, reason);
    }
}
