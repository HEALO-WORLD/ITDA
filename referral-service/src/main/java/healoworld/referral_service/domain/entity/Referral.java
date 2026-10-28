package healoworld.referral_service.domain.entity;

import healoworld.referral_service.domain.enums.ReferralStatus;
import healoworld.referral_service.global.entity.BaseEntity;
import jakarta.persistence.*;

import java.util.UUID;

@Entity
@Table(name = "p_referrals")
public class Referral extends BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID referringInstitutionId;

    @Column(nullable = false, updatable = false)
    private UUID referrerId;

    @Column(nullable = false)
    private UUID examTypeId;

    @Column(nullable = false)
    private UUID currentReservationId;

    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private ReferralStatus referralStatus;

    @Column(nullable = false, length = 20)
    private String patientName;

    @Column(nullable = false, unique = true)
    private UUID idempotencyKey;
}
