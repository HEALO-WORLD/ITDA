package healoworld.slot_service.domain.entity;

import healoworld.slot_service.domain.enums.SlotStatus;
import healoworld.slot_service.domain.enums.SlotStatusChangeReason;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

// 슬롯 상태 변경 이력. 추가만 하고 수정·삭제하지 않으므로 감사 컬럼(BaseEntity)을 두지 않는다
@Entity
@Table(name = "p_slot_status_histories",
        indexes = @Index(name = "idx_slot_status_history_slot_changed_at", columnList = "slot_id, changed_at"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SlotStatusHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    // 상태 변경은 대부분 벌크 UPDATE로 처리하므로 slotRepository.getReferenceById(slotId)로 조회 없이 연결한다
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "slot_id", nullable = false, updatable = false)
    private Slot slot;

    // Referral Service의 p_reservations.id를 UUID로 참조
    @Column(name = "reservation_id", updatable = false)
    private UUID reservationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", nullable = false, updatable = false, length = 20)
    private SlotStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, updatable = false, length = 20)
    private SlotStatus toStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason", nullable = false, updatable = false, length = 30)
    private SlotStatusChangeReason reason;

    // Kafka 이벤트로 바뀐 경우 원본 eventId. API·스케줄러로 바뀐 경우 NULL
    @Column(name = "source_event_id", updatable = false)
    private UUID sourceEventId;

    @Column(name = "changed_at", nullable = false, updatable = false)
    private LocalDateTime changedAt;

    // API로 바뀐 경우 요청 사용자. 이벤트·스케줄러로 바뀐 경우 NULL
    @Column(name = "changed_by", updatable = false)
    private UUID changedBy;

    private SlotStatusHistory(
            Slot slot,
            UUID reservationId,
            SlotStatus fromStatus,
            SlotStatus toStatus,
            SlotStatusChangeReason reason,
            UUID sourceEventId,
            LocalDateTime changedAt,
            UUID changedBy
    ) {
        this.slot = Objects.requireNonNull(slot, "슬롯은 필수입니다.");
        this.fromStatus = Objects.requireNonNull(fromStatus, "이전 상태는 필수입니다.");
        this.toStatus = Objects.requireNonNull(toStatus, "이후 상태는 필수입니다.");
        this.reason = Objects.requireNonNull(reason, "변경 사유는 필수입니다.");
        this.changedAt = Objects.requireNonNull(changedAt, "상태 변경 일자는 필수입니다.");
        this.reservationId = reservationId;
        this.sourceEventId = sourceEventId;
        this.changedBy = changedBy;
    }

    // Kafka 이벤트 수신으로 상태가 바뀐 경우
    public static SlotStatusHistory ofEvent(
            Slot slot,
            UUID reservationId,
            SlotStatus fromStatus,
            SlotStatus toStatus,
            SlotStatusChangeReason reason,
            UUID sourceEventId,
            LocalDateTime changedAt
    ) {
        Objects.requireNonNull(sourceEventId, "원본 이벤트 ID는 필수입니다.");
        return new SlotStatusHistory(slot, reservationId, fromStatus, toStatus, reason, sourceEventId, changedAt, null);
    }

    // API 요청(ADMIN 강제 해제, 장비 중단 등록 등)으로 상태가 바뀐 경우
    public static SlotStatusHistory ofUser(
            Slot slot,
            UUID reservationId,
            SlotStatus fromStatus,
            SlotStatus toStatus,
            SlotStatusChangeReason reason,
            LocalDateTime changedAt,
            UUID changedBy
    ) {
        Objects.requireNonNull(changedBy, "변경한 사용자는 필수입니다.");
        return new SlotStatusHistory(slot, reservationId, fromStatus, toStatus, reason, null, changedAt, changedBy);
    }

    // 스케줄러(HOLD 만료 등)로 상태가 바뀐 경우
    public static SlotStatusHistory ofSystem(
            Slot slot,
            UUID reservationId,
            SlotStatus fromStatus,
            SlotStatus toStatus,
            SlotStatusChangeReason reason,
            LocalDateTime changedAt
    ) {
        return new SlotStatusHistory(slot, reservationId, fromStatus, toStatus, reason, null, changedAt, null);
    }
}
