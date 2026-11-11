package healoworld.referral_service.domain.entity;

import healoworld.referral_service.domain.enums.Reason;
import healoworld.referral_service.domain.enums.ReferralStatus;
import healoworld.referral_service.global.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "p_referral_histories")
@Getter
@Builder(access = AccessLevel.PRIVATE)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@SQLRestriction("deleted_at IS NULL")
public class ReferralHistory extends BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "referral_id", nullable = false)
    private Referral referral;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reservation_id", nullable = false)
    private Reservation reservation;

    // 이 상태 변경을 일으킨 이벤트 ID
    // - Kafka 이벤트로 바뀐 경우: 받은 이벤트의 eventId (보상 커맨드를 함께 발행해도 받은 eventId를 남긴다. 발행 커맨드는 Outbox의 aggregate_id로 찾을 수 있다)
    // - 사용자 요청(HTTP)으로 바뀐 경우: 받은 이벤트가 없으므로 그 요청으로 발행한 커맨드의 Outbox eventId
    private UUID eventId;

    @Column(length = 20)
    @Enumerated(EnumType.STRING)
    private ReferralStatus fromStatus;

    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private ReferralStatus toStatus;

    @Column(nullable = false, length = 30)
    @Enumerated(EnumType.STRING)
    private Reason reason;

    @Column(nullable = false)
    private LocalDateTime occurredAt;
    private UUID changedBy;

    public static ReferralHistory create(Referral referral, Reservation reservation, ReferralStatus fromStatus, ReferralStatus toStatus, Reason reason, UUID eventId, UUID userId) {
        return ReferralHistory.builder()
                .referral(referral)
                .reservation(reservation)
                .eventId(eventId)
                .fromStatus(fromStatus)
                .toStatus(toStatus)
                .reason(reason)
                .occurredAt(LocalDateTime.now())
                .changedBy(userId)
                .build();
    }
}
