package healoworld.institution_service.domain.entity;

import healoworld.institution_service.domain.enums.EquipmentStatus;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "p_equipment_status_histories", indexes = {
        @Index(name = "ix_equipment_status_history_equipment_changed", columnList = "equipment_id,changed_at,id")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EquipmentStatusHistory {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "equipment_id", nullable = false, updatable = false)
    private UUID equipmentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", nullable = false, updatable = false, length = 20)
    private EquipmentStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, updatable = false, length = 20)
    private EquipmentStatus toStatus;

    @Column(nullable = false, updatable = false, length = 500)
    private String reason;

    @Column(name = "changed_by", nullable = false, updatable = false)
    private UUID changedBy;

    @Column(name = "changed_at", nullable = false, updatable = false)
    private LocalDateTime changedAt;

    // 업무 변경 이력은 수정·삭제하지 않는다. 등록 이력은 별도 생성하지 않는다.
    public static EquipmentStatusHistory create(UUID equipmentId, EquipmentStatus fromStatus,
                                                EquipmentStatus toStatus, String reason, UUID changedBy, LocalDateTime changedAt) {
        Objects.requireNonNull(equipmentId, "장비 ID는 필수입니다.");
        Objects.requireNonNull(fromStatus, "이전 상태는 필수입니다.");
        Objects.requireNonNull(toStatus, "변경 상태는 필수입니다.");
        Objects.requireNonNull(changedBy, "변경자는 필수입니다.");
        Objects.requireNonNull(changedAt, "변경 시각은 필수입니다.");
        if (fromStatus == toStatus || reason == null || reason.isBlank() || reason.length() > 500) {
            throw new IllegalArgumentException("실제 상태 변경과 500자 이내의 사유가 필요합니다.");
        }
        EquipmentStatusHistory history = new EquipmentStatusHistory();
        history.equipmentId = equipmentId;
        history.fromStatus = fromStatus;
        history.toStatus = toStatus;
        history.reason = reason.strip();
        history.changedBy = changedBy;
        history.changedAt = changedAt;
        return history;
    }
}
