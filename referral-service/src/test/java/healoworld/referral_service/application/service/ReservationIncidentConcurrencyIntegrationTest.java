package healoworld.referral_service.application.service;

import healoworld.referral_service.application.event.result.SlotResultType;
import healoworld.referral_service.application.service.IncidentProcessingService.Outcome;
import healoworld.referral_service.domain.entity.*;
import healoworld.referral_service.domain.enums.ExaminationStatus;
import healoworld.referral_service.domain.enums.Reason;
import healoworld.referral_service.domain.enums.ReferralStatus;
import healoworld.referral_service.domain.enums.ReservationStatus;
import healoworld.referral_service.domain.enums.RoleType;
import healoworld.referral_service.global.config.ClockConfig;
import healoworld.referral_service.global.config.QuerydslConfig;
import healoworld.referral_service.global.exception.BaseException;
import healoworld.referral_service.global.exception.ErrorCode;
import healoworld.referral_service.global.inbox.ConsumedEventId;
import healoworld.referral_service.global.inbox.ConsumedEventRepository;
import healoworld.referral_service.global.outbox.OutboxEventRepository;
import healoworld.referral_service.global.outbox.SlotCommandOutbox;
import healoworld.referral_service.infrastructure.repository.*;
import healoworld.referral_service.support.PostgresContainerSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
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
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * 예약 취소와 장비 장애 처리·검사 시작/완료의 동시성 통합 테스트.
 * <p>
 * - 실제 ReservationService / IncidentProcessingService / ExaminationService 호출
 * - 확정 예약은 실제 예약 확정(SlotBooked)처럼 같은 예약에 연결된 SCHEDULED 검사와 함께 만든다
 *   (취소는 검사가 SCHEDULED일 때만 허용하고, 검사가 없으면 거부한다)
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
        SlotResultService.class,
        EquipmentIncidentService.class,
        EquipmentIncidentLockRepository.class,
        QuerydslConfig.class, ClockConfig.class,
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

    @Autowired
    private ExaminationRepository examinationRepository;

    @Autowired
    private ExaminationService examinationService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private SlotResultService slotResultService;

    @Autowired
    private ConsumedEventRepository consumedEventRepository;

    private final List<UUID> referralIds = new ArrayList<>();
    private final List<UUID> consumedEventIds = new ArrayList<>();
    private final List<UUID> reservationIds = new ArrayList<>();
    private final List<UUID> incidentIds = new ArrayList<>();
    private final List<UUID> taskIds = new ArrayList<>();

    private record Booking(
            UUID referralId,
            UUID reservationId,
            UUID userId,
            UUID requesterInstitutionId,
            UUID equipmentId,
            LocalDateTime startsAt,
            UUID examinationId,
            UUID examInstitutionId
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
     * 실제 예약 확정(SlotBooked)처럼 이 예약에 연결된 SCHEDULED 검사도 함께 만든다.
     */
    private Booking confirmedBooking() {
        return booking(true);
    }

    /**
     * confirmReferral이 false면 예약만 확정되고 의뢰는 PENDING인 상태(Saga 진행 중)로 만든다.
     * 취소 도중 의뢰 변경이 실패하는 롤백 상황을 실제 DB로 재현하기 위해 쓴다.
     */
    private Booking booking(boolean confirmReferral) {

        LocalDateTime startsAt = LocalDateTime.now()
                .plusDays(3)
                .withHour(14)
                .withMinute(0)
                .withSecond(0)
                .withNano(0);

        UUID userId = UUID.randomUUID();
        UUID requesterInstitutionId = UUID.randomUUID();
        UUID equipmentId = UUID.randomUUID();
        UUID examInstitutionId = UUID.randomUUID();

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
                    examInstitutionId,
                    equipmentId,
                    startsAt,
                    startsAt,
                    startsAt.plusHours(1)
            );

            reservation.hold();
            reservation.confirm();

            reservationRepository.save(reservation);

            referral.assignReservation(reservation.getId());
            if (confirmReferral) {
                referral.confirm();
            }

            Examination examination = examinationRepository.save(
                    Examination.schedule(referral.getId(), reservation.getId(), examInstitutionId)
            );

            return new Booking(
                    referral.getId(),
                    reservation.getId(),
                    userId,
                    requesterInstitutionId,
                    equipmentId,
                    startsAt,
                    examination.getId(),
                    examInstitutionId
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

    // =========================================================
    // 검사 상태에 따른 취소 제한 (SCHEDULED일 때만 취소)
    // =========================================================

    private void startExamination(Booking booking) {
        examinationService.changeStatus(booking.examinationId(), booking.examInstitutionId(), RoleType.EXAMINER,
                ExaminationStatus.IN_PROGRESS);
    }

    private void completeExamination(Booking booking) {
        examinationService.changeStatus(booking.examinationId(), booking.examInstitutionId(), RoleType.EXAMINER,
                ExaminationStatus.COMPLETED);
    }

    private ExaminationStatus examinationStatus(Booking booking) {
        return examinationRepository.findById(booking.examinationId()).orElseThrow().getStatus();
    }

    private List<ReferralHistory> histories(Booking booking) {
        return referralHistoryRepository.findAllByReferralIdOrderByOccurredAtAsc(booking.referralId());
    }

    private static void assertErrorCode(Throwable error, ErrorCode expected) {
        assertThat(error).isInstanceOf(BaseException.class);
        assertThat(((BaseException) error).getErrorCode()).isEqualTo(expected);
    }

    // 취소가 거부됐을 때: 예약·의뢰는 확정 그대로, ReleaseSlot·이력은 생기지 않는다
    private void assertCancelHadNoEffect(Booking booking, int historyCountBefore) {
        assertThat(reservationStatus(booking)).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(referralStatus(booking)).isEqualTo(ReferralStatus.CONFIRMED);
        assertThat(releaseSlotCount(booking)).isZero();
        assertThat(histories(booking)).hasSize(historyCountBefore);
    }

    @Test
    @DisplayName("A. SCHEDULED 검사의 예약은 취소되고 검사는 그대로이며 ReleaseSlot 1건·취소 이력이 남는다")
    void cancelScheduledExamination() {
        Booking booking = confirmedBooking();

        cancel(booking);

        assertThat(reservationStatus(booking)).isEqualTo(ReservationStatus.CANCELED);
        assertThat(referralStatus(booking)).isEqualTo(ReferralStatus.CANCELED);
        assertThat(examinationStatus(booking)).isEqualTo(ExaminationStatus.SCHEDULED);
        assertThat(releaseSlotCount(booking)).isEqualTo(1);
        assertThat(histories(booking)).singleElement().satisfies(history -> {
            assertThat(history.getFromStatus()).isEqualTo(ReferralStatus.CONFIRMED);
            assertThat(history.getToStatus()).isEqualTo(ReferralStatus.CANCELED);
            assertThat(history.getReason()).isEqualTo(Reason.CANCELED_BY_REQUESTER);
        });
    }

    @Test
    @DisplayName("B. 검사가 시작된(IN_PROGRESS) 뒤의 취소는 409 EXAMINATION_CANCEL_NOT_ALLOWED이고 아무것도 바뀌지 않는다")
    void cancelRejectedWhenInProgress() {
        Booking booking = confirmedBooking();
        startExamination(booking);
        int historyCount = histories(booking).size();

        assertErrorCode(catchThrowable(() -> cancel(booking)), ErrorCode.EXAMINATION_CANCEL_NOT_ALLOWED);

        assertThat(examinationStatus(booking)).isEqualTo(ExaminationStatus.IN_PROGRESS);
        assertCancelHadNoEffect(booking, historyCount);
    }

    @Test
    @DisplayName("C. 검사가 완료된(COMPLETED) 뒤의 취소는 409이고 아무것도 바뀌지 않는다")
    void cancelRejectedWhenCompleted() {
        Booking booking = confirmedBooking();
        startExamination(booking);
        completeExamination(booking);
        int historyCount = histories(booking).size();

        assertErrorCode(catchThrowable(() -> cancel(booking)), ErrorCode.EXAMINATION_CANCEL_NOT_ALLOWED);

        assertThat(examinationStatus(booking)).isEqualTo(ExaminationStatus.COMPLETED);
        assertCancelHadNoEffect(booking, historyCount);
    }

    @Test
    @DisplayName("D. 예약을 CANCELED로 바꾼 뒤 의뢰 변경이 실패하면 예약 변경까지 롤백되고 ReleaseSlot·이력이 남지 않는다")
    void cancelRollsBackWhenReferralUpdateFails() {
        Booking booking = booking(false);   // 예약은 확정, 의뢰는 PENDING (의뢰 취소 조건부 UPDATE가 0건이 된다)

        assertErrorCode(catchThrowable(() -> cancel(booking)), ErrorCode.INVALID_REFERRAL_STATUS);

        assertThat(reservationStatus(booking)).isEqualTo(ReservationStatus.CONFIRMED);   // 이미 바꾼 예약도 롤백됨
        assertThat(referralStatus(booking)).isEqualTo(ReferralStatus.PENDING);
        assertThat(examinationStatus(booking)).isEqualTo(ExaminationStatus.SCHEDULED);
        assertThat(releaseSlotCount(booking)).isZero();
        assertThat(histories(booking)).isEmpty();
    }

    @Test
    @DisplayName("검사가 없는 확정 예약은 상태를 확인할 수 없어 취소를 거부한다 (fail-closed)")
    void cancelRejectedWithoutExamination() {
        Booking booking = confirmedBooking();
        tx().executeWithoutResult(status -> examinationRepository.deleteById(booking.examinationId()));

        assertErrorCode(catchThrowable(() -> cancel(booking)), ErrorCode.INVALID_RESERVATION_STATUS);
        assertCancelHadNoEffect(booking, 0);
    }

    @Test
    @DisplayName("취소 우선: 취소된 예약의 검사는 시작할 수 없고 SCHEDULED로 남는다")
    void startRejectedAfterCancel() {
        Booking booking = confirmedBooking();
        cancel(booking);

        assertErrorCode(catchThrowable(() -> startExamination(booking)), ErrorCode.EXAMINATION_START_NOT_ALLOWED);
        assertThat(examinationStatus(booking)).isEqualTo(ExaminationStatus.SCHEDULED);
        assertThat(reservationStatus(booking)).isEqualTo(ReservationStatus.CANCELED);
    }

    // ---------- 잠금 순서를 고정한 결정적 경쟁 (한쪽이 커밋 전 잠금을 쥔 동안 다른 쪽이 기다리는지) ----------

    @Test
    @DisplayName("[결정적] 취소가 의뢰 잠금을 쥔 동안 검사 시작은 기다렸다가, 취소 커밋 후 시작이 거부된다")
    void startWaitsForCancelThenRejected() throws Exception {
        Booking booking = confirmedBooking();
        CountDownLatch cancelDone = new CountDownLatch(1);
        CountDownLatch releaseCancel = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            // 취소를 바깥 트랜잭션 안에서 실행하고 커밋을 늦춘다 (잠금을 쥔 채 대기)
            Future<?> cancelTx = executor.submit(() -> tx().executeWithoutResult(status -> {
                cancel(booking);
                cancelDone.countDown();
                await(releaseCancel);
            }));
            assertThat(cancelDone.await(10, TimeUnit.SECONDS)).isTrue();

            Future<Throwable> start = executor.submit(() -> catchThrowable(() -> startExamination(booking)));
            waitUntilSomeSessionWaitsForLock();
            assertThat(start.isDone()).as("검사 시작은 취소의 잠금이 풀릴 때까지 기다려야 한다").isFalse();

            releaseCancel.countDown();
            cancelTx.get(30, TimeUnit.SECONDS);
            assertErrorCode(start.get(30, TimeUnit.SECONDS), ErrorCode.EXAMINATION_START_NOT_ALLOWED);
        } finally {
            releaseCancel.countDown();
            executor.shutdownNow();
        }

        assertThat(examinationStatus(booking)).isEqualTo(ExaminationStatus.SCHEDULED);
        assertThat(reservationStatus(booking)).isEqualTo(ReservationStatus.CANCELED);
        assertThat(referralStatus(booking)).isEqualTo(ReferralStatus.CANCELED);
        assertThat(releaseSlotCount(booking)).isEqualTo(1);
    }

    @Test
    @DisplayName("[결정적] 검사 시작이 잠금을 쥔 동안 취소는 기다렸다가, 시작 커밋 후 409로 거부된다")
    void cancelWaitsForStartThenRejected() throws Exception {
        Booking booking = confirmedBooking();
        CountDownLatch startDone = new CountDownLatch(1);
        CountDownLatch releaseStart = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> startTx = executor.submit(() -> tx().executeWithoutResult(status -> {
                startExamination(booking);
                startDone.countDown();
                await(releaseStart);
            }));
            assertThat(startDone.await(10, TimeUnit.SECONDS)).isTrue();

            Future<Throwable> cancelResult = executor.submit(() -> catchThrowable(() -> cancel(booking)));
            waitUntilSomeSessionWaitsForLock();
            assertThat(cancelResult.isDone()).as("취소는 검사 시작의 잠금이 풀릴 때까지 기다려야 한다").isFalse();

            releaseStart.countDown();
            startTx.get(30, TimeUnit.SECONDS);
            assertErrorCode(cancelResult.get(30, TimeUnit.SECONDS), ErrorCode.EXAMINATION_CANCEL_NOT_ALLOWED);
        } finally {
            releaseStart.countDown();
            executor.shutdownNow();
        }

        assertThat(examinationStatus(booking)).isEqualTo(ExaminationStatus.IN_PROGRESS);
        assertCancelHadNoEffect(booking, 0);
    }

    // ---------- 실제 동시 실행 (반복) ----------

    @RepeatedTest(5)
    @DisplayName("[동시] 검사 시작과 취소를 같은 순간에 시작해도 한쪽만 성공하고, 시작된 검사의 예약이 취소되지 않는다")
    void concurrentStartAndCancel() throws Exception {
        Booking booking = confirmedBooking();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(1);
        Throwable cancelError;
        Throwable startError;
        try {
            Future<Throwable> cancelFuture = executor.submit(() -> {
                ready.await();
                return catchThrowable(() -> cancel(booking));
            });
            Future<Throwable> startFuture = executor.submit(() -> {
                ready.await();
                return catchThrowable(() -> startExamination(booking));
            });
            ready.countDown();
            cancelError = cancelFuture.get(30, TimeUnit.SECONDS);
            startError = startFuture.get(30, TimeUnit.SECONDS);
        } finally {
            ready.countDown();
            executor.shutdownNow();
        }

        String state = "cancelError=" + cancelError + ", startError=" + startError + ", reservation="
                + reservationStatus(booking) + ", examination=" + examinationStatus(booking);
        assertThat(cancelError == null ^ startError == null).as("정확히 한쪽만 성공해야 한다: " + state).isTrue();
        if (cancelError == null) {
            assertErrorCode(startError, ErrorCode.EXAMINATION_START_NOT_ALLOWED);
            assertThat(examinationStatus(booking)).as(state).isEqualTo(ExaminationStatus.SCHEDULED);
            assertThat(reservationStatus(booking)).as(state).isEqualTo(ReservationStatus.CANCELED);
            assertThat(releaseSlotCount(booking)).as(state).isEqualTo(1);
        } else {
            assertErrorCode(cancelError, ErrorCode.EXAMINATION_CANCEL_NOT_ALLOWED);
            assertThat(examinationStatus(booking)).as(state).isEqualTo(ExaminationStatus.IN_PROGRESS);
            assertCancelHadNoEffect(booking, 0);
        }
    }

    @RepeatedTest(3)
    @DisplayName("[동시] 검사 완료와 취소를 같은 순간에 시작해도 취소는 항상 거부되고 검사는 완료된다")
    void concurrentCompleteAndCancel() throws Exception {
        Booking booking = confirmedBooking();
        startExamination(booking);
        int historyCount = histories(booking).size();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(1);
        Throwable cancelError;
        Throwable completeError;
        try {
            Future<Throwable> cancelFuture = executor.submit(() -> {
                ready.await();
                return catchThrowable(() -> cancel(booking));
            });
            Future<Throwable> completeFuture = executor.submit(() -> {
                ready.await();
                return catchThrowable(() -> completeExamination(booking));
            });
            ready.countDown();
            cancelError = cancelFuture.get(30, TimeUnit.SECONDS);
            completeError = completeFuture.get(30, TimeUnit.SECONDS);
        } finally {
            ready.countDown();
            executor.shutdownNow();
        }

        assertThat(completeError).isNull();
        assertErrorCode(cancelError, ErrorCode.EXAMINATION_CANCEL_NOT_ALLOWED);
        assertThat(examinationStatus(booking)).isEqualTo(ExaminationStatus.COMPLETED);
        assertCancelHadNoEffect(booking, historyCount);
    }

    // ---------- 검사 시작 ↔ SlotsBlocked (정합성 복구 등으로 들어오는 슬롯 차단 결과) ----------

    private void slotsBlocked(Booking booking) {
        UUID eventId = UUID.randomUUID();
        consumedEventIds.add(eventId);
        slotResultService.handle(eventId, SlotResultType.SLOTS_BLOCKED, booking.reservationId());
    }

    private long blockedHistoryCount(Booking booking) {
        return histories(booking).stream().filter(h -> h.getReason() == Reason.RESERVATION_BLOCKED).count();
    }

    // 검사가 시작된 예약은 SlotsBlocked로 바뀌지 않는다: 예약·의뢰 확정 그대로, 차단 이력 없음
    private void assertNotBlockedAfterStart(Booking booking) {
        assertThat(examinationStatus(booking)).isEqualTo(ExaminationStatus.IN_PROGRESS);
        assertThat(reservationStatus(booking)).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(referralStatus(booking)).isEqualTo(ReferralStatus.CONFIRMED);
        assertThat(blockedHistoryCount(booking)).isZero();
    }

    // 차단이 먼저 반영된 예약: 예약 BLOCKED, 의뢰 NEEDS_RESCHEDULE, 검사는 시작되지 않고 차단 이력 1건
    private void assertBlockedBeforeStart(Booking booking) {
        assertThat(examinationStatus(booking)).isEqualTo(ExaminationStatus.SCHEDULED);
        assertThat(reservationStatus(booking)).isEqualTo(ReservationStatus.BLOCKED);
        assertThat(referralStatus(booking)).isEqualTo(ReferralStatus.NEEDS_RESCHEDULE);
        assertThat(blockedHistoryCount(booking)).isEqualTo(1);
    }

    @Test
    @DisplayName("[결정적] 검사 시작이 잠금을 쥔 동안 SlotsBlocked는 기다렸다가, 시작 커밋 후 예약·의뢰를 바꾸지 않는다")
    void slotsBlockedWaitsForStartThenSkips() throws Exception {
        Booking booking = confirmedBooking();
        CountDownLatch startDone = new CountDownLatch(1);
        CountDownLatch releaseStart = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> startTx = executor.submit(() -> tx().executeWithoutResult(status -> {
                startExamination(booking);
                startDone.countDown();
                await(releaseStart);
            }));
            assertThat(startDone.await(10, TimeUnit.SECONDS)).isTrue();

            Future<Throwable> blocked = executor.submit(() -> catchThrowable(() -> slotsBlocked(booking)));
            waitUntilSomeSessionWaitsForLock();
            assertThat(blocked.isDone()).as("SlotsBlocked는 검사 시작의 잠금이 풀릴 때까지 기다려야 한다").isFalse();

            releaseStart.countDown();
            startTx.get(30, TimeUnit.SECONDS);
            assertThat(blocked.get(30, TimeUnit.SECONDS)).as("트랜잭션 오류 없이 끝나야 한다").isNull();
        } finally {
            releaseStart.countDown();
            executor.shutdownNow();
        }

        assertNotBlockedAfterStart(booking);
    }

    @Test
    @DisplayName("[결정적] SlotsBlocked가 잠금을 쥔 동안 검사 시작은 기다렸다가, 차단 커밋 후 시작이 거부된다")
    void startWaitsForSlotsBlockedThenRejected() throws Exception {
        Booking booking = confirmedBooking();
        CountDownLatch blockDone = new CountDownLatch(1);
        CountDownLatch releaseBlock = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> blockTx = executor.submit(() -> tx().executeWithoutResult(status -> {
                slotsBlocked(booking);
                blockDone.countDown();
                await(releaseBlock);
            }));
            assertThat(blockDone.await(10, TimeUnit.SECONDS)).isTrue();

            Future<Throwable> start = executor.submit(() -> catchThrowable(() -> startExamination(booking)));
            waitUntilSomeSessionWaitsForLock();
            assertThat(start.isDone()).as("검사 시작은 SlotsBlocked의 잠금이 풀릴 때까지 기다려야 한다").isFalse();

            releaseBlock.countDown();
            blockTx.get(30, TimeUnit.SECONDS);
            assertErrorCode(start.get(30, TimeUnit.SECONDS), ErrorCode.EXAMINATION_START_NOT_ALLOWED);
        } finally {
            releaseBlock.countDown();
            executor.shutdownNow();
        }

        assertBlockedBeforeStart(booking);
    }

    @RepeatedTest(5)
    @DisplayName("[동시] 검사 시작과 SlotsBlocked를 같은 순간에 실행해도 한쪽만 반영되고, 다시 받아도 이력이 늘지 않는다")
    void concurrentStartAndSlotsBlocked() throws Exception {
        Booking booking = confirmedBooking();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(1);
        Throwable blockError;
        Throwable startError;
        try {
            Future<Throwable> blockFuture = executor.submit(() -> {
                ready.await();
                return catchThrowable(() -> slotsBlocked(booking));
            });
            Future<Throwable> startFuture = executor.submit(() -> {
                ready.await();
                return catchThrowable(() -> startExamination(booking));
            });
            ready.countDown();
            blockError = blockFuture.get(30, TimeUnit.SECONDS);
            startError = startFuture.get(30, TimeUnit.SECONDS);
        } finally {
            ready.countDown();
            executor.shutdownNow();
        }

        String state = "blockError=" + blockError + ", startError=" + startError + ", reservation="
                + reservationStatus(booking) + ", examination=" + examinationStatus(booking);
        assertThat(blockError).as("SlotsBlocked는 트랜잭션 오류 없이 끝나야 한다: " + state).isNull();
        if (startError == null) {
            assertNotBlockedAfterStart(booking);
        } else {
            assertErrorCode(startError, ErrorCode.EXAMINATION_START_NOT_ALLOWED);
            assertBlockedBeforeStart(booking);
        }

        // 같은 예약에 SlotsBlocked가 다시 와도(재전달·복구 회차) 상태·이력이 바뀌지 않는다
        long historyCount = histories(booking).size();
        ReservationStatus reservationBefore = reservationStatus(booking);
        slotsBlocked(booking);
        assertThat(histories(booking)).as(state).hasSize((int) historyCount);
        assertThat(reservationStatus(booking)).as(state).isEqualTo(reservationBefore);
    }

    // ---------- SlotsBlocked ↔ 장애 후속 처리 순서와 재조정 알림 (ALREADY_BLOCKED 알림 보완) ----------

    @Test
    @DisplayName("A. SlotsBlocked가 먼저 차단하면 장애 작업은 ALREADY_BLOCKED이고, 재조정 알림은 정확히 1건 생긴다")
    void slotsBlockedFirstThenIncidentCreatesOneNotification() {
        Booking booking = confirmedBooking();
        UUID taskId = pendingTask(booking);

        slotsBlocked(booking);
        assertThat(reservationStatus(booking)).isEqualTo(ReservationStatus.BLOCKED);
        assertThat(referralStatus(booking)).isEqualTo(ReferralStatus.NEEDS_RESCHEDULE);
        assertThat(notificationsFor(booking)).isEmpty();   // SlotsBlocked는 알림을 만들지 않는다

        assertThat(incidentProcessingService.process(taskId)).isEqualTo(Outcome.SUCCEEDED);

        assertThat(incidentResult(taskId)).isEqualTo(IncidentProcessingService.ALREADY_BLOCKED);
        assertThat(notificationsFor(booking)).hasSize(1);
        assertThat(notificationsFor(booking).getFirst().getReservationId()).isEqualTo(booking.reservationId());
        assertThat(blockedHistoryCount(booking)).isEqualTo(1);   // 이력은 SlotsBlocked가 남긴 1건뿐
    }

    @Test
    @DisplayName("B. 장애 작업이 먼저 차단하면 알림 1건, 이후 SlotsBlocked가 와도 알림·이력은 그대로다")
    void incidentFirstThenSlotsBlockedKeepsOneNotification() {
        Booking booking = confirmedBooking();
        UUID taskId = pendingTask(booking);

        assertThat(incidentProcessingService.process(taskId)).isEqualTo(Outcome.SUCCEEDED);
        assertThat(incidentResult(taskId)).isEqualTo(IncidentProcessingService.RESCHEDULE_REQUIRED);
        assertThat(notificationsFor(booking)).hasSize(1);

        slotsBlocked(booking);

        assertThat(reservationStatus(booking)).isEqualTo(ReservationStatus.BLOCKED);
        assertThat(notificationsFor(booking)).hasSize(1);
        assertThat(blockedHistoryCount(booking)).isEqualTo(1);
    }

    @Test
    @DisplayName("C. SlotsBlocked 뒤 서로 다른 장애 작업 2건이 같은 예약을 처리해도 알림은 1건이다")
    void differentTasksOfSameReservationCreateOneNotification() {
        Booking booking = confirmedBooking();
        UUID first = pendingTask(booking);
        UUID second = pendingTask(booking);

        slotsBlocked(booking);
        incidentProcessingService.process(first);
        incidentProcessingService.process(second);

        assertThat(List.of(incidentResult(first), incidentResult(second)))
                .containsOnly(IncidentProcessingService.ALREADY_BLOCKED);
        assertThat(notificationsFor(booking)).hasSize(1);
    }

    @Test
    @DisplayName("C. (동시) SlotsBlocked 뒤 서로 다른 장애 작업 2건을 동시에 처리해도 알림은 1건이다")
    void concurrentDifferentTasksCreateOneNotification() throws Exception {
        Booking booking = confirmedBooking();
        UUID first = pendingTask(booking);
        UUID second = pendingTask(booking);
        slotsBlocked(booking);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(1);
        try {
            Future<Outcome> a = executor.submit(() -> {
                ready.await();
                return incidentProcessingService.process(first);
            });
            Future<Outcome> b = executor.submit(() -> {
                ready.await();
                return incidentProcessingService.process(second);
            });
            ready.countDown();
            assertThat(a.get(30, TimeUnit.SECONDS)).isEqualTo(Outcome.SUCCEEDED);
            assertThat(b.get(30, TimeUnit.SECONDS)).isEqualTo(Outcome.SUCCEEDED);
        } finally {
            ready.countDown();
            executor.shutdownNow();
        }

        assertThat(notificationsFor(booking)).hasSize(1);
        assertThat(blockedHistoryCount(booking)).isEqualTo(1);
    }

    @Test
    @DisplayName("D. 같은 예약의 재조정 알림이 이미 있으면 ALREADY_BLOCKED 작업은 새 알림을 만들지 않는다")
    void alreadyBlockedWithExistingNotificationCreatesNone() {
        Booking booking = confirmedBooking();
        UUID first = pendingTask(booking);
        incidentProcessingService.process(first);   // RESCHEDULE_REQUIRED, 알림 1건
        UUID existingNotificationId = notificationsFor(booking).getFirst().getId();
        UUID second = pendingTask(booking);

        incidentProcessingService.process(second);

        assertThat(incidentResult(second)).isEqualTo(IncidentProcessingService.ALREADY_BLOCKED);
        assertThat(notificationsFor(booking)).extracting(Notification::getId).containsExactly(existingNotificationId);
    }

    @Test
    @DisplayName("E. 검사가 IN_PROGRESS면 SlotsBlocked·장애 작업 모두 예약·의뢰를 바꾸지 않고 재조정 알림이 없다")
    void startedExaminationGetsNoNotification() {
        Booking booking = confirmedBooking();
        UUID taskId = pendingTask(booking);
        startExamination(booking);

        slotsBlocked(booking);
        incidentProcessingService.process(taskId);

        assertThat(incidentResult(taskId)).isEqualTo(IncidentProcessingService.SKIPPED_IN_PROGRESS);
        assertThat(reservationStatus(booking)).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(referralStatus(booking)).isEqualTo(ReferralStatus.CONFIRMED);
        assertThat(notificationsFor(booking)).isEmpty();
        assertThat(blockedHistoryCount(booking)).isZero();
    }

    @Test
    @DisplayName("E. 검사가 COMPLETED면 SlotsBlocked·장애 작업 모두 예약·의뢰를 바꾸지 않고 재조정 알림이 없다")
    void completedExaminationGetsNoNotification() {
        Booking booking = confirmedBooking();
        UUID taskId = pendingTask(booking);
        startExamination(booking);
        completeExamination(booking);

        slotsBlocked(booking);
        incidentProcessingService.process(taskId);

        assertThat(incidentResult(taskId)).isEqualTo(IncidentProcessingService.SKIPPED_COMPLETED);
        assertThat(reservationStatus(booking)).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(referralStatus(booking)).isEqualTo(ReferralStatus.CONFIRMED);
        assertThat(notificationsFor(booking)).isEmpty();
        assertThat(blockedHistoryCount(booking)).isZero();
    }

    @Test
    @DisplayName("F. 같은 장애 작업을 다시 처리해도(이미 성공) 선점되지 않아 알림이 늘지 않는다")
    void reprocessingSameTaskCreatesNoDuplicate() {
        Booking booking = confirmedBooking();
        UUID taskId = pendingTask(booking);
        slotsBlocked(booking);

        assertThat(incidentProcessingService.process(taskId)).isEqualTo(Outcome.SUCCEEDED);
        assertThat(incidentProcessingService.process(taskId)).isEqualTo(Outcome.NOT_CLAIMED);

        assertThat(incidentResult(taskId)).isEqualTo(IncidentProcessingService.ALREADY_BLOCKED);
        assertThat(notificationsFor(booking)).hasSize(1);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("대기 시간 초과");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    // 다른 세션이 행 잠금을 기다리기 시작할 때까지 기다린다 (테스트 DB의 pg_stat_activity 확인)
    private void waitUntilSomeSessionWaitsForLock() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbcTemplate.queryForObject("""
                    select count(*) from pg_stat_activity
                     where datname = current_database() and wait_event_type = 'Lock'
                    """, Integer.class);
            if (waiting != null && waiting > 0) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("잠금을 기다리는 세션이 10초 안에 나타나지 않았다");
    }

    /**
     * 테스트가 만든 데이터만 정리.
     */
    @AfterEach
    void cleanUp() {

        consumedEventRepository.deleteAllById(consumedEventIds.stream()
                .map(eventId -> new ConsumedEventId(eventId, SlotResultService.CONSUMER))
                .toList());

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

        for (UUID referralId : referralIds) {
            examinationRepository.findByReferralId(referralId).ifPresent(examinationRepository::delete);
        }

        reservationRepository.deleteAllById(reservationIds);

        referralRepository.deleteAllById(referralIds);
    }
}
