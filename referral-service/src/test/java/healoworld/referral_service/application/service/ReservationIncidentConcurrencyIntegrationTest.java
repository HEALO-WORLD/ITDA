package healoworld.referral_service.application.service;

import healoworld.referral_service.application.service.IncidentProcessingService.Outcome;
import healoworld.referral_service.domain.entity.*;
import healoworld.referral_service.domain.enums.ReferralStatus;
import healoworld.referral_service.domain.enums.ReservationStatus;
import healoworld.referral_service.domain.enums.RoleType;
import healoworld.referral_service.global.config.QuerydslConfig;
import healoworld.referral_service.global.exception.BaseException;
import healoworld.referral_service.global.exception.ErrorCode;
import healoworld.referral_service.global.outbox.OutboxEventRepository;
import healoworld.referral_service.global.outbox.SlotCommandOutbox;
import healoworld.referral_service.infrastructure.repository.*;
import healoworld.referral_service.support.PostgresContainerSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 예약 취소와 장비 장애 처리의 동시성 통합 테스트.
 * <p>
 * - 실제 ReservationService / IncidentProcessingService 호출
 * - PostgreSQL 테스트 DB 사용
 * - 각 스레드는 독립 트랜잭션으로 처리
 * - Kafka / Gateway를 거치는 E2E 테스트는 아님
 */
@DataJpaTest
@Import({
        IncidentProcessingService.class,
        NotificationService.class,
        ExaminationService.class,
        ReservationService.class,
        ReferralHistoryService.class,
        SlotCommandOutbox.class,
        QuerydslConfig.class,
        ReservationIncidentConcurrencyIntegrationTest.JsonConfig.class
})
@ActiveProfiles("test")
@AutoConfigureTestDatabase(
        replace = AutoConfigureTestDatabase.Replace.NONE
)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ReservationIncidentConcurrencyIntegrationTest extends PostgresContainerSupport {

    @TestConfiguration(proxyBeanMethods = false)
    static class JsonConfig {

        @Bean
        JsonMapper jsonMapper() {
            return JsonMapper.builder().build();
        }
    }

    @Autowired
    private ReservationService reservationService;

    @Autowired
    private IncidentProcessingService incidentProcessingService;

    @Autowired
    private ReferralRepository referralRepository;

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private EquipmentIncidentRepository equipmentIncidentRepository;

    @Autowired
    private IncidentReferralRepository incidentReferralRepository;

    @Autowired
    private ReferralHistoryRepository referralHistoryRepository;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private final List<UUID> referralIds = new ArrayList<>();
    private final List<UUID> reservationIds = new ArrayList<>();
    private final List<UUID> incidentIds = new ArrayList<>();
    private final List<UUID> taskIds = new ArrayList<>();

    private record Booking(
            UUID referralId,
            UUID reservationId,
            UUID userId,
            UUID requesterInstitutionId,
            UUID equipmentId,
            LocalDateTime startsAt
    ) {
    }

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }

    /**
     * 테스트용 CONFIRMED 예약 생성.
     * <p>
     * 예약 취소는 검사 시작 2시간 전까지만 가능하므로
     * 검사 시작일을 현재보다 3일 뒤로 설정한다.
     */
    private Booking confirmedBooking() {

        LocalDateTime startsAt = LocalDateTime.now()
                .plusDays(3)
                .withHour(14)
                .withMinute(0)
                .withSecond(0)
                .withNano(0);

        UUID userId = UUID.randomUUID();
        UUID requesterInstitutionId = UUID.randomUUID();
        UUID equipmentId = UUID.randomUUID();

        Booking booking = tx().execute(status -> {

            Referral referral = referralRepository.save(
                    Referral.create(
                            requesterInstitutionId,
                            userId,
                            UUID.randomUUID(),
                            "테스트환자",
                            UUID.randomUUID()
                    )
            );

            Reservation reservation = Reservation.create(
                    referral,
                    1,
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    equipmentId,
                    startsAt,
                    startsAt,
                    startsAt.plusHours(1)
            );

            reservation.hold();
            reservation.confirm();

            reservationRepository.save(reservation);

            referral.assignReservation(reservation.getId());
            referral.confirm();

            return new Booking(
                    referral.getId(),
                    reservation.getId(),
                    userId,
                    requesterInstitutionId,
                    equipmentId,
                    startsAt
            );
        });

        referralIds.add(booking.referralId());
        reservationIds.add(booking.reservationId());

        return booking;
    }

    /**
     * 예약에 영향을 주는 장비 장애 작업 생성.
     */
    private UUID pendingTask(Booking booking) {

        UUID taskId = tx().execute(status -> {

            EquipmentIncident incident =
                    equipmentIncidentRepository.save(
                            EquipmentIncident.occur(
                                    UUID.randomUUID(),
                                    booking.equipmentId(),
                                    UUID.randomUUID(),
                                    "동시성 테스트 장애",
                                    booking.startsAt(),
                                    booking.startsAt(),
                                    booking.startsAt().plusHours(2)
                            )
                    );

            incidentIds.add(incident.getId());

            return incidentReferralRepository.save(
                    IncidentReferral.pending(
                            incident,
                            booking.referralId(),
                            booking.reservationId()
                    )
            ).getId();
        });

        taskIds.add(taskId);

        return taskId;
    }

    private void cancel(Booking booking) {

        reservationService.cancelReservation(
                booking.userId(),
                booking.requesterInstitutionId(),
                RoleType.REQUESTER,
                booking.reservationId()
        );
    }

    private ReservationStatus reservationStatus(Booking booking) {

        return reservationRepository
                .findById(booking.reservationId())
                .orElseThrow()
                .getReservationStatus();
    }

    private ReferralStatus referralStatus(Booking booking) {

        return referralRepository
                .findById(booking.referralId())
                .orElseThrow()
                .getReferralStatus();
    }

    private String incidentResult(UUID taskId) {

        return incidentReferralRepository
                .findById(taskId)
                .orElseThrow()
                .getResultCode();
    }

    private List<Notification> notificationsFor(Booking booking) {

        return notificationRepository.findAll()
                .stream()
                .filter(notification ->
                        booking.referralId().equals(notification.getReferralId())
                )
                .toList();
    }

    private long releaseSlotCount(Booking booking) {

        return outboxEventRepository.findAll()
                .stream()
                .filter(event ->
                        booking.reservationId().equals(event.getAggregateId())
                )
                .filter(event ->
                        "ReleaseSlot".equals(event.getEventType())
                )
                .count();
    }

    /**
     * TEST 1
     * 예약 취소가 먼저 완료된 경우.
     */
    @Test
    @DisplayName("취소 우선: 장애 처리는 취소된 예약을 건너뛴다")
    void cancelBeforeIncident() {

        Booking booking = confirmedBooking();
        UUID taskId = pendingTask(booking);

        cancel(booking);

        assertThat(incidentProcessingService.process(taskId))
                .isEqualTo(Outcome.SUCCEEDED);

        assertThat(reservationStatus(booking))
                .isEqualTo(ReservationStatus.CANCELED);

        assertThat(referralStatus(booking))
                .isEqualTo(ReferralStatus.CANCELED);

        assertThat(incidentResult(taskId))
                .isEqualTo(
                        IncidentProcessingService.SKIPPED_CANCELED
                );

        assertThat(notificationsFor(booking)).isEmpty();

        assertThat(releaseSlotCount(booking)).isEqualTo(1);
    }

    /**
     * TEST 2
     * 장비 장애 처리가 먼저 완료된 경우.
     */
    @Test
    @DisplayName("장애 우선: 차단된 예약의 취소 요청을 거절한다")
    void incidentBeforeCancel() {

        Booking booking = confirmedBooking();
        UUID taskId = pendingTask(booking);

        assertThat(incidentProcessingService.process(taskId))
                .isEqualTo(Outcome.SUCCEEDED);

        assertThatThrownBy(() -> cancel(booking))
                .isInstanceOf(BaseException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.INVALID_RESERVATION_STATUS);

        assertThat(reservationStatus(booking))
                .isEqualTo(ReservationStatus.BLOCKED);

        assertThat(referralStatus(booking))
                .isEqualTo(ReferralStatus.NEEDS_RESCHEDULE);

        assertThat(incidentResult(taskId))
                .isEqualTo(
                        IncidentProcessingService.RESCHEDULE_REQUIRED
                );

        assertThat(notificationsFor(booking)).hasSize(1);

        assertThat(releaseSlotCount(booking)).isZero();
    }

    /**
     * TEST 3
     * 두 서비스를 실제로 동시에 실행.
     * <p>
     * 어느 쪽이 먼저 잠금을 획득하든
     * 최종 상태가 일관성을 유지해야 한다.
     */
    @Test
    @DisplayName("동시 실행: 예약 취소와 장애 처리의 최종 상태 정합성")
    void concurrentCancelAndIncident() throws Exception {

        Booking booking = confirmedBooking();
        UUID taskId = pendingTask(booking);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);

        try {

            Future<Boolean> cancelFuture = executor.submit(() -> {

                start.await();

                try {
                    cancel(booking);
                    return true;

                } catch (BaseException exception) {

                    if (exception.getErrorCode()
                            != ErrorCode.INVALID_RESERVATION_STATUS) {
                        throw exception;
                    }

                    return false;
                }
            });

            Future<Outcome> incidentFuture = executor.submit(() -> {

                start.await();

                return incidentProcessingService.process(taskId);
            });

            // 두 작업을 동시에 시작
            start.countDown();

            boolean cancellationSucceeded =
                    cancelFuture.get(30, TimeUnit.SECONDS);

            Outcome outcome =
                    incidentFuture.get(30, TimeUnit.SECONDS);

            assertThat(outcome).isEqualTo(Outcome.SUCCEEDED);

            if (cancellationSucceeded) {

                // 예약 취소가 먼저 완료된 경우
                assertThat(reservationStatus(booking))
                        .isEqualTo(ReservationStatus.CANCELED);

                assertThat(referralStatus(booking))
                        .isEqualTo(ReferralStatus.CANCELED);

                assertThat(incidentResult(taskId))
                        .isEqualTo(
                                IncidentProcessingService.SKIPPED_CANCELED
                        );

                assertThat(notificationsFor(booking)).isEmpty();

                assertThat(releaseSlotCount(booking)).isEqualTo(1);

            } else {

                // 장비 장애 처리가 먼저 완료된 경우
                assertThat(reservationStatus(booking))
                        .isEqualTo(ReservationStatus.BLOCKED);

                assertThat(referralStatus(booking))
                        .isEqualTo(ReferralStatus.NEEDS_RESCHEDULE);

                assertThat(incidentResult(taskId))
                        .isEqualTo(
                                IncidentProcessingService.RESCHEDULE_REQUIRED
                        );

                assertThat(notificationsFor(booking)).hasSize(1);

                assertThat(releaseSlotCount(booking)).isZero();
            }

        } finally {

            start.countDown();
            executor.shutdownNow();
        }
    }

    /**
     * TEST 4
     * 동일 예약에 취소 요청 2건 동시 발생.
     */
    @Test
    @DisplayName("중복 취소: 실제 취소 1건, ReleaseSlot 이벤트 1건")
    void concurrentCancelSameReservation() throws Exception {

        Booking booking = confirmedBooking();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);

        try {

            Callable<Boolean> request = () -> {

                start.await();

                try {
                    cancel(booking);
                    return true;

                } catch (BaseException exception) {

                    if (exception.getErrorCode()
                            != ErrorCode.INVALID_RESERVATION_STATUS) {
                        throw exception;
                    }

                    return false;
                }
            };

            Future<Boolean> first = executor.submit(request);
            Future<Boolean> second = executor.submit(request);

            start.countDown();

            assertThat(List.of(
                    first.get(30, TimeUnit.SECONDS),
                    second.get(30, TimeUnit.SECONDS)
            )).containsExactlyInAnyOrder(true, false);

            assertThat(reservationStatus(booking))
                    .isEqualTo(ReservationStatus.CANCELED);

            assertThat(referralStatus(booking))
                    .isEqualTo(ReferralStatus.CANCELED);

            assertThat(releaseSlotCount(booking)).isEqualTo(1);

        } finally {

            start.countDown();
            executor.shutdownNow();
        }
    }

    /**
     * 테스트가 만든 데이터만 정리.
     */
    @AfterEach
    void cleanUp() {

        notificationRepository.deleteAll(
                notificationRepository.findAll()
                        .stream()
                        .filter(notification ->
                                referralIds.contains(notification.getReferralId())
                        )
                        .toList()
        );

        for (UUID referralId : referralIds) {

            referralHistoryRepository.deleteAll(
                    referralHistoryRepository
                            .findAllByReferralIdOrderByOccurredAtAsc(referralId)
            );
        }

        outboxEventRepository.deleteAll(
                outboxEventRepository.findAll()
                        .stream()
                        .filter(event ->
                                reservationIds.contains(event.getAggregateId())
                        )
                        .toList()
        );

        incidentReferralRepository.deleteAllById(taskIds);

        equipmentIncidentRepository.deleteAllById(incidentIds);

        reservationRepository.deleteAllById(reservationIds);

        referralRepository.deleteAllById(referralIds);
    }
}
