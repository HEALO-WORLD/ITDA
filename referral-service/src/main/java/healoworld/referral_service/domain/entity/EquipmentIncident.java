package healoworld.referral_service.domain.entity;

import healoworld.referral_service.domain.enums.EquipmentIncidentStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
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

// p_equipment_incidents: 장비 장애 사건 1건 (Kafka 장애 이벤트 1건당 1행)
// ERD에 created_by/deleted_at 컬럼이 없으므로 BaseEntity를 상속하지 않는다
// 시각 컬럼은 ERD 스냅샷(2026-10-28) 기준 TIMESTAMP(LocalDateTime)
@Getter
@Entity
@Table(name = "p_equipment_incidents",
        uniqueConstraints = @UniqueConstraint(name = "uk_equipment_incidents_source_event_id",
                columnNames = "source_event_id"))
@EntityListeners(AuditingEntityListener.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EquipmentIncident {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    // 원본 Kafka 이벤트 ID. 같은 이벤트가 중복 전달돼도 UNIQUE 제약으로 사건이 두 번 생기지 않는다
    @Column(nullable = false, updatable = false)
    private UUID sourceEventId;

    // Institution Service의 장비·기관 ID (다른 서비스 DB이므로 FK 없이 값만 보관)
    @Column(nullable = false, updatable = false)
    private UUID equipmentId;

    @Column(nullable = false, updatable = false)
    private UUID institutionId;

    @Column(nullable = false, length = 500)
    private String reason;

    @Enumerated(EnumType.STRING)
    @ColumnDefault("'OCCURRED'")
    @Column(nullable = false, length = 100)
    private EquipmentIncidentStatus status;

    @Column(nullable = false)
    private LocalDateTime occurredAt;

    // 장애 영향 구간. 이 구간과 겹치는 확정 예약이 영향 의뢰가 된다
    @Column(nullable = false)
    private LocalDateTime startAt;

    @Column(nullable = false)
    private LocalDateTime endAt;

    // 아래 세 컬럼은 "영향 의뢰 조회 작업"의 재시도용 (의뢰별 재처리는 IncidentReferral이 담당)
    // TODO: 조회 작업 재시도 정책이 확정되면 값을 바꾸는 조건부 UPDATE를 추가한다
    @ColumnDefault("0")
    @Column(nullable = false)
    private int retryCount;

    private LocalDateTime nextRetryAt;

    @Column(length = 500)
    private String lastError;

    @CreatedDate
    @ColumnDefault("CURRENT_TIMESTAMP")
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @ColumnDefault("CURRENT_TIMESTAMP")
    @Column(nullable = false)
    private LocalDateTime updatedAt;

    public static EquipmentIncident occur(UUID sourceEventId, UUID equipmentId, UUID institutionId, String reason,
                                          LocalDateTime occurredAt, LocalDateTime startAt, LocalDateTime endAt) {
        EquipmentIncident incident = new EquipmentIncident();
        incident.sourceEventId = sourceEventId;
        incident.equipmentId = equipmentId;
        incident.institutionId = institutionId;
        incident.reason = reason;
        incident.occurredAt = occurredAt;
        incident.startAt = startAt;
        incident.endAt = endAt;
        incident.status = EquipmentIncidentStatus.OCCURRED;
        return incident;
    }

    // 같은 원본 이벤트가 다시 전달된 것인지 확인한다 (장비·기관·발생 시각·영향 구간이 모두 같아야 같은 장애)
    // 사유(reason)는 설명 문구라 비교하지 않는다
    public boolean isSameEvent(UUID equipmentId, UUID institutionId, LocalDateTime occurredAt,
                               LocalDateTime startAt, LocalDateTime endAt) {
        return this.equipmentId.equals(equipmentId)
                && this.institutionId.equals(institutionId)
                && this.occurredAt.equals(occurredAt)
                && this.startAt.equals(startAt)
                && this.endAt.equals(endAt);
    }
}
