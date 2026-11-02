package healoworld.institution_service.domain.entity;

import healoworld.institution_service.domain.enums.Modality;
import healoworld.institution_service.global.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.Objects;
import java.util.UUID;

@Entity
@Getter
@Table(name = "p_exam_types")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ExamType extends BaseEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "name", nullable = false, length = 50)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "modality", nullable = false, length = 20)
    private Modality modality;

    @Column(name = "duration_minutes", nullable = false)
    private Integer durationMinutes;

    private ExamType(String name, Modality modality, Integer durationMinutes) {

        validateName(name);

        this.name = name;
        this.modality = Objects.requireNonNull(modality,"검사 종류는 필수입니다.");

        if (durationMinutes == null || durationMinutes <= 0) {
            throw new IllegalArgumentException("검사 소요시간은 필수이며 1분 이상이어야 합니다.");
        }

        this.durationMinutes = durationMinutes;
    }

    public static ExamType create(String name, Modality modality,Integer durationMinutes) {
        return new ExamType(name, modality, durationMinutes);
    }

    private  void validateName(String name) {
        if (name == null || name.isBlank() || name.length() > 50) {
            throw new IllegalArgumentException("검사명은 공백이 아닌 최대 50자여야 합니다.");
        }
    }
}
