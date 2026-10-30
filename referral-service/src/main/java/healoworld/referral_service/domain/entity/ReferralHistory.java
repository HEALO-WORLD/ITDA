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

    @Column(nullable = false)
    private UUID eventId;

    @Column(length = 20)
    @Enumerated(EnumType.STRING)
    private ReferralStatus fromStatus;

    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private ReferralStatus toStatus;

    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private Reason reason;

    private LocalDateTime occurredAt;

    public static ReferralHistory create(Referral referral, Reservation reservation, ReferralStatus fromStatus, ReferralStatus toStatus, Reason reason, UUID eventId) {
         return ReferralHistory.builder()
                 .referral(referral)
                 .reservation(reservation)
                 .eventId(eventId)
                 .fromStatus(fromStatus)
                 .toStatus(toStatus)
                 .reason(reason)
                 .occurredAt(LocalDateTime.now())
                 .build();
    }
}
