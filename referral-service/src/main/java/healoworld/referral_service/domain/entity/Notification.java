package healoworld.referral_service.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;
import java.util.UUID;

// p_notifications: 사용자에게 보여줄 알림
// ERD에 created_by/updated_at/deleted_at 컬럼이 없으므로 BaseEntity를 상속하지 않는다
@Getter
@Entity
@Table(name = "p_notifications")
@EntityListeners(AuditingEntityListener.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    // 같은 서비스의 p_referrals / p_reservations / p_incident_referrals를 가리키지만,
    // 해당 엔티티가 아직 없으므로 우선 UUID 값으로만 보관한다
    // 의뢰별 알림 관리를 위해 referral_id는 필수
    @Column(nullable = false)
    private UUID referralId;

    // 특정 예약 시도와 연결되는 알림에만 저장한다
    private UUID reservationId;

    private UUID incidentReferralId;

    @Column(nullable = false)
    private UUID recipientUserId;

    // 알림을 만든 원인 Kafka 이벤트 ID (중복 생성 방지 키 후보)
    private UUID sourceEventId;

    // 알림 유형 값 목록이 아직 확정되지 않아 enum 대신 문자열로 둔다
    @Column(nullable = false, length = 50)
    private String type;

    @Column(nullable = false, length = 100)
    private String title;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String message;

    @Column(nullable = false)
    private boolean isRead;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime readAt;

    public static Notification create(UUID recipientUserId, UUID referralId, UUID reservationId,
                                      UUID incidentReferralId, UUID sourceEventId,
                                      String type, String title, String message) {
        Notification notification = new Notification();
        notification.recipientUserId = recipientUserId;
        notification.referralId = referralId;
        notification.reservationId = reservationId;
        notification.incidentReferralId = incidentReferralId;
        notification.sourceEventId = sourceEventId;
        notification.type = type;
        notification.title = title;
        notification.message = message;
        notification.isRead = false;
        return notification;
    }

    public boolean isOwnedBy(UUID userId) {
        return recipientUserId.equals(userId);
    }
}
