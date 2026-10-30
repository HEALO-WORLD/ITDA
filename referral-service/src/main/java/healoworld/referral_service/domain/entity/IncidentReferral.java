package healoworld.referral_service.domain.entity;

import healoworld.referral_service.domain.enums.IncidentProcessingStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.ColumnDefault;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;
import java.util.UUID;

// p_incident_referrals: 장애 1건 × 영향받은 예약 시도 1건의 후속 처리 작업
// 실패한 작업만 골라 재처리할 수 있도록 의뢰별로 상태·재시도·잠금을 따로 관리한다
// ERD에 created_by/deleted_at 컬럼이 없으므로 BaseEntity를 상속하지 않는다
// 상태·잠금 변경은 동시 실행을 막기 위해 IncidentReferralRepository의 조건부 UPDATE로만 수행한다 (setter 없음)
@Getter
@Entity
@Table(name = "p_incident_referrals",
        uniqueConstraints = @UniqueConstraint(name = "uk_incident_referrals_incident_reservation",
                columnNames = {"incident_id", "reservation_id"}))
@EntityListeners(AuditingEntityListener.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class IncidentReferral {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    // 같은 Referral DB 안의 테이블이므로 실제 FK로 연결한다
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "incident_id", nullable = false, updatable = false)
    private EquipmentIncident incident;

    // 장애 당시의 예약 시도. 재예약으로 의뢰의 현재 예약이 바뀌어도 이 값은 그대로다
    // Reservation·Referral 엔티티가 아직 이 브랜치에 없으므로 UUID 값으로만 보관한다
    @Column(nullable = false, updatable = false)
    private UUID reservationId;

    @Column(nullable = false, updatable = false)
    private UUID referralId;

    @Enumerated(EnumType.STRING)
    @ColumnDefault("'PENDING'")
    @Column(nullable = false, length = 10)
    private IncidentProcessingStatus processingStatus;

    // 실패 후 다시 시도한 횟수 (FAILED → PROCESSING 선점 시 1 증가)
    @ColumnDefault("0")
    @Column(nullable = false)
    private int retryCount;

    private LocalDateTime nextRetryAt;

    // 작업자가 이 시각까지 작업을 선점한다. 지나면 다른 작업자가 가져갈 수 있다 (NULL = 선점 없음)
    private LocalDateTime lockedUntil;

    @Column(length = 500)
    private String lastError;

    // TODO: 결과 코드 값 목록은 미확정이라 문자열로 보관한다
    @Column(length = 20)
    private String resultCode;

    private LocalDateTime processedAt;

    @CreatedDate
    @ColumnDefault("CURRENT_TIMESTAMP")
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @ColumnDefault("CURRENT_TIMESTAMP")
    @Column(nullable = false)
    private LocalDateTime updatedAt;

    public static IncidentReferral pending(EquipmentIncident incident, UUID referralId, UUID reservationId) {
        IncidentReferral incidentReferral = new IncidentReferral();
        incidentReferral.incident = incident;
        incidentReferral.referralId = referralId;
        incidentReferral.reservationId = reservationId;
        incidentReferral.processingStatus = IncidentProcessingStatus.PENDING;
        return incidentReferral;
    }
}
