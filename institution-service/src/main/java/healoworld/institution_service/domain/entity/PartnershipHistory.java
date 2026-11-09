package healoworld.institution_service.domain.entity;

import healoworld.institution_service.domain.enums.PartnershipStatus;
import healoworld.institution_service.global.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.UUID;

// 협약 상태 변경 이력: INSERT만, 수정 메서드 없음
// 변경 시각/ 변경한 사용자 = created_at/created_by (BaseEntoity 자동 기록, X-User-Id)
@Entity
@Table(name = "p_partnership_histories")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PartnershipHistory extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "partnership_id", nullable = false, updatable = false)
    private Partnership partnership;

    @Enumerated(EnumType.STRING)
    @Column(length = 20, updatable = false)
    private PartnershipStatus fromStatus;  // 첫 요청이면 null

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, updatable = false)
    private PartnershipStatus toStatus;

    public static PartnershipHistory create(Partnership partnership,
                                            PartnershipStatus fromStatus,
                                            PartnershipStatus toStatus) {
        PartnershipHistory history = new PartnershipHistory();
        history.partnership = partnership;
        history.fromStatus = fromStatus;
        history.toStatus = toStatus;
        return history;
    }

}
