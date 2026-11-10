package healoworld.referral_service.infrastructure.repository;

import healoworld.referral_service.domain.entity.InstitutionNotificationRead;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

public interface InstitutionNotificationReadRepository extends JpaRepository<InstitutionNotificationRead, UUID> {

    // 이 사용자의 읽음 기록이 없을 때만 저장한다. 저장했으면 1, 이미 있으면 0
    // - UNIQUE(notification_id, user_id) 충돌은 예외 대신 0건이므로, 같은 사용자의 재요청·동시 요청에도 처음 기록(read_at)이 유지된다
    //   (동시 요청은 먼저 넣은 트랜잭션의 커밋을 기다린 뒤 0건이 된다)
    // - 다른 사용자의 행은 키가 달라 건드리지 않는다
    // - 호출한 쪽(읽음 처리) 트랜잭션에 참여한다
    @Modifying(flushAutomatically = true)
    @Query(value = """
            insert into p_institution_notification_reads (id, notification_id, user_id, read_at)
            values (:id, :notificationId, :userId, :readAt)
            on conflict do nothing
            """, nativeQuery = true)
    int insertIfAbsent(@Param("id") UUID id,
                       @Param("notificationId") UUID notificationId,
                       @Param("userId") UUID userId,
                       @Param("readAt") LocalDateTime readAt);

    // 이 사용자가 처음 읽은 시각 (읽음 처리 응답용)
    @Query("""
            select r.readAt from InstitutionNotificationRead r
             where r.notificationId = :notificationId and r.userId = :userId
            """)
    Optional<LocalDateTime> findReadAt(@Param("notificationId") UUID notificationId, @Param("userId") UUID userId);
}
