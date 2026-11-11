package healoworld.institution_service.domain.entity;

import healoworld.institution_service.domain.enums.Modality;
import healoworld.institution_service.domain.enums.EquipmentStatus;
import healoworld.institution_service.global.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "p_equipments")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Equipment extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "institution_id", nullable = false, updatable = false)
    private UUID institutionId;

    @Column(name = "name", nullable = false, length = 50)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "modality", nullable = false, length = 20)
    private Modality modality;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private EquipmentStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "manual_status", length = 20)
    private EquipmentStatus manualStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "downtime_status", length = 20)
    private EquipmentStatus downtimeStatus;

    @Column(name = "downtime_revision", nullable = false)
    private long downtimeRevision;

    private Equipment(
            UUID institutionId,
            String name,
            Modality modality
    ) {
        this.institutionId = Objects.requireNonNull(institutionId, "소속기관 ID는 필수입니다.");

        this.name = normalizeName(name);
        this.modality = Objects.requireNonNull(modality, "장비 종류는 필수입니다.");
        this.status = EquipmentStatus.OPERATIONAL;
        this.manualStatus = EquipmentStatus.OPERATIONAL;
    }

    public static Equipment create(
            UUID institutionId,
            String name,
            Modality modality
    ) {
        return new Equipment(institutionId, name, modality);
    }

    public void changeStatus(EquipmentStatus status) {
        this.manualStatus = Objects.requireNonNull(status, "장비 상태는 필수입니다.");
        recomputeStatus();
    }

    public boolean applyDowntimeStatus(long revision, EquipmentStatus target) {
        if (revision <= downtimeRevision) {
            return false;
        }
        if (manualStatus == null) {
            manualStatus = status; // 기존 데이터 보존. 배포 전 SQL에서도 백필한다.
        }
        downtimeRevision = revision;
        downtimeStatus = target == EquipmentStatus.OPERATIONAL ? null : target;
        recomputeStatus();
        return true;
    }

    private void recomputeStatus() {
        if (manualStatus == EquipmentStatus.FAILED || downtimeStatus == EquipmentStatus.FAILED) {
            status = EquipmentStatus.FAILED;
        } else if (manualStatus == EquipmentStatus.MAINTENANCE || downtimeStatus == EquipmentStatus.MAINTENANCE) {
            status = EquipmentStatus.MAINTENANCE;
        } else {
            status = EquipmentStatus.OPERATIONAL;
        }
    }

    public void changeName(String name) {
        this.name = normalizeName(name);
    }

    private static String normalizeName(String name) {
        String normalizedName = name == null ? null : name.trim();
        if (normalizedName == null || normalizedName.isBlank() || normalizedName.length() > 50) {
            throw new IllegalArgumentException("장비명은 공백이 아닌 최대 50자여야 합니다.");
        }
        return normalizedName;
    }
}
