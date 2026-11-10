package healoworld.referral_service.domain.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

// p_institution_notifications: 검사기관 공통 알림 본문 (기관당 1건, 기관 소속 사용자 모두가 같은 알림을 조회한다)
// 읽음 여부는 사용자마다 따로 관리하므로 이 테이블에 두지 않는다 → p_institution_notification_reads (InstitutionNotificationRead)
// 사용자 개인 알림(p_notifications)은 recipient_user_id가 필수이고 중복 방지 제약·인덱스가 사용자 기준이라 그대로 두고 테이블을 나눈다
// 저장은 InstitutionNotificationRepository.insertIfAbsent()(INSERT ... ON CONFLICT DO NOTHING)로만 한다
//  - 같은 기관·예약·유형의 알림은 UNIQUE 제약으로 1건만 남고, 중복이어도 예외 없이 건너뛰어 예약 확정·취소 트랜잭션을 롤백시키지 않는다
// 실제 환경 DDL: db/manual/institution_notifications.sql (ddl-auto와 같은 구조, 절차는 institution_notifications.md)
@Getter
@Entity
@Table(
        name = "p_institution_notifications",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_institution_notifications_institution_reservation_type",
                columnNames = {"institution_id", "reservation_id", "type"}
        ),
        indexes = @Index(
                name = "idx_institution_notifications_institution_created",
                columnList = "institution_id, created_at"
        )
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InstitutionNotification {

    @Id
    private UUID id;

    // 수신 검사기관 ID (institution-service의 기관 ID, DB FK 없음)
    @Column(nullable = false)
    private UUID institutionId;

    @Column(nullable = false)
    private UUID referralId;

    // 알림 대상 예약. 검사기관이 상세를 열 수 있는 자기 기관 예약을 가리킨다 (다른 기관으로 변경 알림은 이전 예약)
    @Column(nullable = false)
    private UUID reservationId;

    // 알림을 만든 원인 이벤트 ID (확정: SlotBooked 이벤트, 취소: ReleaseSlot Outbox 이벤트)
    private UUID sourceEventId;

    @Column(nullable = false, length = 50)
    private String type;

    @Column(nullable = false, length = 100)
    private String title;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String message;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public boolean isOwnedBy(UUID institutionId) {
        return this.institutionId.equals(institutionId);
    }
}
