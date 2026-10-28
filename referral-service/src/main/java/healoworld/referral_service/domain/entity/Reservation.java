package healoworld.referral_service.domain.entity;

import healoworld.referral_service.domain.enums.FailureReason;
import healoworld.referral_service.domain.enums.ReservationStatus;
import healoworld.referral_service.global.entity.BaseEntity;
import jakarta.persistence.*;

import java.sql.Timestamp;
import java.util.UUID;

@Entity
@Table(name = "p_reservations")
public class Reservation extends BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "referral_id", nullable = false)
    private Referral referral;

    @Column(nullable = false)
    private Integer attemptNo;

    @Column(nullable = false)
    private UUID slotId;

    @Column(nullable = false)
    private UUID examInstitutionId;

    @Column(nullable = false)
    private UUID equipmentId;

    @Column(nullable = false)
    private Timestamp examDate;

    @Column(nullable = false)
    private Timestamp startTime;

    @Column(nullable = false)
    private Timestamp endTime;

    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private ReservationStatus reservationStatus;

    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private FailureReason failureReason;

    private Timestamp confirmedAt;
    private Timestamp endedAt;
}
