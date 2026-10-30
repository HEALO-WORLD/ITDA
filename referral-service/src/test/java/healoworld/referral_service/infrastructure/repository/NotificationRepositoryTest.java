package healoworld.referral_service.infrastructure.repository;

import healoworld.referral_service.domain.entity.Notification;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 실제 PostgreSQL(docker-compose의 referral_db)에 연결해 쿼리를 검증한다
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class NotificationRepositoryTest {

    @Autowired
    private NotificationRepository notificationRepository;

    // 예약 시도와 연결되지 않은 알림 (reservationId = null)
    private Notification save(UUID recipientUserId, UUID referralId) {
        return save(recipientUserId, referralId, null);
    }

    private Notification save(UUID recipientUserId, UUID referralId, UUID reservationId) {
        return notificationRepository.saveAndFlush(Notification.create(recipientUserId, referralId, reservationId,
                null, null, "TEST", "테스트 알림", "가상 테스트 메시지"));
    }

    @Test
    @DisplayName("reservationId가 NULL인 알림도 저장되고, 값이 있으면 그대로 보존된다")
    void reservationIdIsOptional() {
        UUID me = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();

        Notification withoutReservation = save(me, UUID.randomUUID());
        Notification withReservation = save(me, UUID.randomUUID(), reservationId);

        assertThat(notificationRepository.findById(withoutReservation.getId()).orElseThrow().getReservationId())
                .isNull();
        assertThat(notificationRepository.findById(withReservation.getId()).orElseThrow().getReservationId())
                .isEqualTo(reservationId);
    }

    @Test
    @DisplayName("referralId가 없으면 DB의 NOT NULL 제약으로 저장이 거부된다")
    void referralIdIsRequired() {
        Notification notification = Notification.create(UUID.randomUUID(), null, null,
                null, null, "TEST", "테스트 알림", "가상 테스트 메시지");

        assertThatThrownBy(() -> notificationRepository.saveAndFlush(notification))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("본인 알림만 조회되고 referralId, isRead 필터가 적용된다")
    void searchMineWithFilters() {
        UUID me = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        UUID referralA = UUID.randomUUID();
        UUID referralB = UUID.randomUUID();

        Notification readA = save(me, referralA);
        save(me, referralA);
        save(me, referralB);
        save(other, referralA);
        notificationRepository.markAsReadIfUnread(readA.getId(), me, LocalDateTime.now());

        PageRequest pageable = PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "createdAt"));

        assertThat(notificationRepository.findAll(NotificationSpecifications.searchMine(me, null, null), pageable)
                .getTotalElements()).isEqualTo(3);
        assertThat(notificationRepository.findAll(NotificationSpecifications.searchMine(me, referralA, null), pageable)
                .getTotalElements()).isEqualTo(2);

        Page<Notification> unreadOfA =
                notificationRepository.findAll(NotificationSpecifications.searchMine(me, referralA, false), pageable);
        assertThat(unreadOfA.getTotalElements()).isEqualTo(1);
        assertThat(unreadOfA.getContent().getFirst().isRead()).isFalse();
    }

    @Test
    @DisplayName("안 읽은 알림 수는 본인의 읽지 않은 알림만 센다")
    void countUnread() {
        UUID me = UUID.randomUUID();
        Notification first = save(me, UUID.randomUUID());
        save(me, UUID.randomUUID());
        save(UUID.randomUUID(), UUID.randomUUID());

        notificationRepository.markAsReadIfUnread(first.getId(), me, LocalDateTime.now());

        assertThat(notificationRepository.countByRecipientUserIdAndIsReadFalse(me)).isEqualTo(1);
    }

    @Test
    @DisplayName("조건부 UPDATE는 처음 한 번만 갱신하고, 다른 사용자의 알림은 갱신하지 않는다")
    void markAsReadIfUnreadIsIdempotent() {
        UUID me = UUID.randomUUID();
        Notification notification = save(me, UUID.randomUUID());
        LocalDateTime firstReadAt = LocalDateTime.now().withNano(0);

        assertThat(notificationRepository.markAsReadIfUnread(notification.getId(), UUID.randomUUID(), firstReadAt))
                .isZero();
        assertThat(notificationRepository.markAsReadIfUnread(notification.getId(), me, firstReadAt)).isEqualTo(1);
        assertThat(notificationRepository.markAsReadIfUnread(notification.getId(), me, firstReadAt.plusMinutes(1)))
                .isZero();

        Notification reloaded = notificationRepository.findById(notification.getId()).orElseThrow();
        assertThat(reloaded.isRead()).isTrue();
        assertThat(reloaded.getReadAt()).isEqualTo(firstReadAt);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED) // 스레드마다 별도 트랜잭션이 커밋된 데이터를 봐야 하므로 테스트 트랜잭션을 끈다
    @DisplayName("동시에 10번 읽음 처리해도 실제 갱신은 1건뿐이다")
    void markAsReadConcurrently() throws Exception {
        UUID me = UUID.randomUUID();
        Notification notification = save(me, UUID.randomUUID());
        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(1);

        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < threadCount; i++) {
                Callable<Integer> task = () -> {
                    ready.await();
                    return notificationRepository.markAsReadIfUnread(notification.getId(), me, LocalDateTime.now());
                };
                results.add(executor.submit(task));
            }
            ready.countDown();

            int updatedRows = 0;
            for (Future<Integer> result : results) {
                updatedRows += result.get();
            }
            assertThat(updatedRows).isEqualTo(1);
        } finally {
            executor.shutdown();
            notificationRepository.deleteById(notification.getId());
        }
    }
}
