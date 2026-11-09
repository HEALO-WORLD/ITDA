package healoworld.referral_service.domain.entity;

import healoworld.referral_service.domain.enums.ExaminationStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.ColumnDefault;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;
import java.util.UUID;

// p_examinations: 의뢰 1건당 하나만 유지되는 실제 검사 진행 기록
// ERD에 created_by/deleted_at 컬럼이 없으므로 BaseEntity를 상속하지 않는다
// 상태·예약 연결 변경은 동시 요청을 막기 위해 ExaminationRepository의 조건부 UPDATE로만 수행한다 (setter 없음)
@Getter
@Entity
@Table(name = "p_examinations")
@EntityListeners(AuditingEntityListener.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Examination {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    // p_referrals를 가리키지만 Referral 엔티티가 아직 이 브랜치에 없으므로 UUID 값으로만 보관한다
    // 재예약해도 검사 기록은 그대로 유지되므로 의뢰 연결은 바뀌지 않는다
    @Column(nullable = false, unique = true, updatable = false)
    private UUID referralId;

    // 현재 유효한 예약 시도. 재예약 시 새 예약 ID로 교체된다
    @Column(nullable = false)
    private UUID reservationId;

    @Column(nullable = false)
    private UUID examInstitutionId;

    @Enumerated(EnumType.STRING)
    @ColumnDefault("'SCHEDULED'")
    @Column(nullable = false, length = 20)
    private ExaminationStatus status;

    private LocalDateTime startedAt;

    private LocalDateTime completedAt;

    @CreatedDate
    @ColumnDefault("CURRENT_TIMESTAMP")
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @ColumnDefault("CURRENT_TIMESTAMP")
    private LocalDateTime updatedAt;

    public static Examination schedule(UUID referralId, UUID reservationId, UUID examInstitutionId) {
        Examination examination = new Examination();
        examination.referralId = referralId;
        examination.reservationId = reservationId;
        examination.examInstitutionId = examInstitutionId;
        examination.status = ExaminationStatus.SCHEDULED;
        return examination;
    }

    // 이미 이 예약·검사기관으로 연결돼 있는지 (같은 확정 처리가 중복 실행됐는지 판단)
    public boolean isLinkedTo(UUID reservationId, UUID examInstitutionId) {
        return this.reservationId.equals(reservationId) && this.examInstitutionId.equals(examInstitutionId);
    }
}
