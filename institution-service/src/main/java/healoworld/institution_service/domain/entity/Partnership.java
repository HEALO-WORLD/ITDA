package healoworld.institution_service.domain.entity;


import healoworld.institution_service.domain.enums.PartnershipStatus;
import healoworld.institution_service.global.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "p_partnerships",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_partnership_pair",
                columnNames = {"requester_institution_id", "exam_institution_id"}
        ))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Partnership extends BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "requester_institution_id", nullable = false)
    private Institution requesterInstitution;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "exam_institution_id", nullable = false)
    private Institution examInstitution;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PartnershipStatus status;

    @Column(nullable = false)
    private LocalDateTime requestedAt;

    private LocalDateTime approvedAt;

    private LocalDateTime terminatedAt;

    public boolean isActive() {
        return status == PartnershipStatus.ACTIVE;
    }

}
