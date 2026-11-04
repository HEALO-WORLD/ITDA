package healoworld.institution_service.domain.entity;

import healoworld.institution_service.global.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.Objects;
import java.util.UUID;

@Entity
@Getter
@Table(name = "p_equipment_exam_types", uniqueConstraints = @UniqueConstraint(name = "uk_equipment_exam_type", columnNames = {"equipment_id", "exam_type_id"}))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EquipmentExamType extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "equipment_id", nullable = false, updatable = false)
    private UUID equipmentId;

    @Column(name = "exam_type_id", nullable = false, updatable = false)
    private UUID examTypeId;

    private EquipmentExamType(Equipment equipment, ExamType examType) {
        Objects.requireNonNull(equipment, "장비는 필수입니다.");
        Objects.requireNonNull(examType, "검사 유형은 필수입니다.");

        if (equipment.getId() == null || examType.getId() == null) {
            throw new IllegalArgumentException("저장된 장비와 검사 유형만 연결할 수 있습니다.");
        }

        if (equipment.getDeletedAt() != null
                || examType.getDeletedAt() != null) {
            throw new IllegalArgumentException("삭제된 장비나 검사 유형은 연결할 수 없습니다.");
        }

        if (equipment.getModality() != examType.getModality()) {
            throw new IllegalArgumentException("장비와 검사 유형의 종류가 일치해야 합니다.");
        }

        this.equipmentId = equipment.getId();
        this.examTypeId = examType.getId();
    }

    public static EquipmentExamType create(
            Equipment equipment,
            ExamType examType
    ) {
        return new EquipmentExamType(equipment, examType);
    }
}