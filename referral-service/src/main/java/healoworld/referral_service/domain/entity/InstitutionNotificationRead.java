package healoworld.referral_service.domain.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

// p_institution_notification_reads: 검사기관 공통 알림의 사용자별 읽음 기록
// 행이 있으면 그 사용자가 읽은 것이고, 없으면 안 읽은 것이다. 같은 기관의 다른 사용자 기록과 서로 영향을 주지 않는다
// UNIQUE(notification_id, user_id)로 사용자당 1건만 남는다. 저장은 InstitutionNotificationReadRepository.insertIfAbsent()
// (INSERT ... ON CONFLICT DO NOTHING)로만 해서, 다시 읽거나 동시에 읽어도 처음 읽은 시각(read_at)이 바뀌지 않는다
// notification_id는 같은 서비스 테이블이지만 알림 본문을 지우는 기능이 없어 FK 없이 ID로만 참조한다
@Getter
@Entity
@Table(
        name = "p_institution_notification_reads",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_institution_notification_reads_notification_user",
                columnNames = {"notification_id", "user_id"}
        )
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InstitutionNotificationRead {

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID notificationId;

    // 읽은 사용자 (Gateway가 JWT에서 꺼내 넣은 X-User-Id)
    @Column(nullable = false)
    private UUID userId;

    // 이 사용자가 처음 읽은 시각
    @Column(nullable = false, updatable = false)
    private LocalDateTime readAt;
}
