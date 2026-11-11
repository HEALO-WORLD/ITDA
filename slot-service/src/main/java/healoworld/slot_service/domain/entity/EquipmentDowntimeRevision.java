package healoworld.slot_service.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 장비별 상태 스냅샷의 순서. 모든 변경은 장비 advisory lock 아래에서 수행한다.
@Entity
@Getter
@Table(name = "p_equipment_downtime_revisions")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EquipmentDowntimeRevision {
    @Id
    @Column(name = "equipment_id", nullable = false, updatable = false)
    private UUID equipmentId;

    @Column(nullable = false)
    private long revision;

    public static EquipmentDowntimeRevision create(UUID equipmentId) {
        EquipmentDowntimeRevision value = new EquipmentDowntimeRevision();
        value.equipmentId = equipmentId;
        return value;
    }

    public long next() {
        return ++revision;
    }
}
