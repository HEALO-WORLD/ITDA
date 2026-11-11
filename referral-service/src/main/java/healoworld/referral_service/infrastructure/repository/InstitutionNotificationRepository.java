package healoworld.referral_service.infrastructure.repository;

import healoworld.referral_service.domain.entity.InstitutionNotification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.UUID;

public interface InstitutionNotificationRepository extends JpaRepository<InstitutionNotification, UUID>,
        InstitutionNotificationRepositoryCustom {

    // 같은 기관·예약·유형의 알림이 없을 때만 저장한다. 저장했으면 1, 이미 있으면 0
    // - 충돌 대상을 지정하지 않은 ON CONFLICT DO NOTHING: UNIQUE 위반을 예외 대신 0건으로 처리하므로
    //   호출한 예약 확정·취소 트랜잭션이 중복 때문에 롤백되지 않는다 (PostgreSQL은 오류가 난 트랜잭션에서 더 실행할 수 없다)
    // - 다른 트랜잭션이 같은 키를 아직 커밋하지 않고 넣고 있으면 그 결과를 기다렸다가, 커밋되면 0건이 된다
    // - 호출한 쪽 트랜잭션에 참여한다. 확정·취소가 롤백되면 알림도 함께 롤백된다
    // - 읽음 여부는 사용자별 테이블(p_institution_notification_reads)에 있으므로 알림 본문에는 넣지 않는다
    @Modifying(flushAutomatically = true)
    @Query(value = """
            insert into p_institution_notifications
                   (id, institution_id, referral_id, reservation_id, source_event_id, type, title, message, created_at)
            values (:id, :institutionId, :referralId, :reservationId, :sourceEventId, :type, :title, :message, :createdAt)
            on conflict do nothing
            """, nativeQuery = true)
    int insertIfAbsent(@Param("id") UUID id,
                       @Param("institutionId") UUID institutionId,
                       @Param("referralId") UUID referralId,
                       @Param("reservationId") UUID reservationId,
                       @Param("sourceEventId") UUID sourceEventId,
                       @Param("type") String type,
                       @Param("title") String title,
                       @Param("message") String message,
                       @Param("createdAt") LocalDateTime createdAt);

    // 기관 알림 중 이 사용자의 읽음 기록이 없는 알림 수 (다른 사용자가 읽어도 줄지 않는다)
    @Query("""
            select count(n) from InstitutionNotification n
             where n.institutionId = :institutionId
               and not exists (select 1 from InstitutionNotificationRead r
                                where r.notificationId = n.id and r.userId = :userId)
            """)
    long countUnreadForUser(@Param("institutionId") UUID institutionId, @Param("userId") UUID userId);
}
