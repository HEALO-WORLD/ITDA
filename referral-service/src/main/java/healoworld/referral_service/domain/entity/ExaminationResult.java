package healoworld.referral_service.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.ColumnDefault;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

// p_examination_results: 검사 1건당 하나만 등록되는 결과 (등록 후 수정하지 않으므로 변경 메서드가 없다)
// ERD에 created_at/updated_at 등 공통 컬럼이 없으므로 BaseEntity를 상속하지 않는다
@Getter
@Entity
@Table(name = "p_examination_results")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ExaminationResult {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    // 같은 서비스·같은 DB의 p_examinations를 가리키므로 FK로 연결한다
    // UNIQUE 제약이 동시 등록 시 중복 결과를 막는 최종 안전장치다
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "examination_id", nullable = false, unique = true, updatable = false)
    private Examination examination;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String summary;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @ColumnDefault("CURRENT_TIMESTAMP")
    @Column(nullable = false, updatable = false)
    private LocalDateTime registeredAt;

    public static ExaminationResult register(Examination examination, String summary, String notes) {
        ExaminationResult result = new ExaminationResult();
        result.examination = examination;
        result.summary = summary;
        result.notes = notes;
        // DB TIMESTAMP 정밀도(마이크로초)에 맞춰 응답 값과 저장 값이 같도록 한다
        result.registeredAt = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        return result;
    }
}
