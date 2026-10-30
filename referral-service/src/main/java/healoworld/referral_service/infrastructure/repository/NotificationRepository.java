package healoworld.referral_service.infrastructure.repository;

import healoworld.referral_service.domain.entity.Notification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

public interface NotificationRepository extends JpaRepository<Notification, UUID>, JpaSpecificationExecutor<Notification> {

    long countByRecipientUserIdAndIsReadFalse(UUID recipientUserId);

    // 아직 안 읽은 알림만 읽음으로 바꾼다 (조건부 UPDATE)
    // 동시에 여러 번 호출돼도 처음 한 번만 1을 반환하고, 이후에는 0을 반환해 readAt이 바뀌지 않는다
    // @Query 수정 메서드는 기본 트랜잭션이 없으므로 직접 선언한다 (서비스 트랜잭션 안에서 호출되면 그 트랜잭션에 참여)
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update Notification n
               set n.isRead = true, n.readAt = :readAt
             where n.id = :id
               and n.recipientUserId = :recipientUserId
               and n.isRead = false
            """)
    int markAsReadIfUnread(@Param("id") UUID id,
                           @Param("recipientUserId") UUID recipientUserId,
                           @Param("readAt") LocalDateTime readAt);
}
