package healoworld.referral_service.application.service;

import healoworld.referral_service.application.service.IncidentProcessingService.Outcome;
import healoworld.referral_service.domain.entity.EquipmentIncident;
import healoworld.referral_service.domain.entity.IncidentReferral;
import healoworld.referral_service.domain.entity.Referral;
import healoworld.referral_service.domain.entity.Reservation;
import healoworld.referral_service.domain.enums.ExaminationStatus;
import healoworld.referral_service.domain.enums.FailureReason;
import healoworld.referral_service.domain.enums.IncidentProcessingStatus;
import healoworld.referral_service.domain.enums.ReferralStatus;
import healoworld.referral_service.domain.enums.ReservationStatus;
import healoworld.referral_service.global.config.QuerydslConfig;
import healoworld.referral_service.global.exception.BaseException;
import healoworld.referral_service.global.exception.ErrorCode;
import healoworld.referral_service.infrastructure.repository.EquipmentIncidentRepository;
import healoworld.referral_service.infrastructure.repository.ExaminationRepository;
import healoworld.referral_service.infrastructure.repository.IncidentAffectedReferralRepository;
import healoworld.referral_service.infrastructure.repository.IncidentReferralRepository;
import healoworld.referral_service.infrastructure.repository.ReferralRepository;
import healoworld.referral_service.infrastructure.repository.ReservationRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

/**
 * IncidentProcessingService 통합 테스트 (테스트 전용 PostgreSQL)
 *
 * - 서비스는 선점·처리·실패 기록을 각각 실제로 커밋하므로 테스트 트랜잭션을 끄고, 테스트가 만든 행만 끝날 때 ID로 지운다.
 * - 운영 코드에는 예약 확정(Saga) 처리가 아직 없으므로, 확정된 의뢰·예약은 도메인 메서드로 직접 만든다.
 *   Kafka·Gateway를 거치는 E2E 검증은 이 테스트의 범위가 아니다.
 */
@DataJpaTest
@ExtendWith(OutputCaptureExtension.class)
@Import({IncidentProcessingService.class, ExaminationService.class, QuerydslConfig.class})
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class IncidentProcessingServiceIntegrationTest {

    private static final LocalDateTime EXAM_START = LocalDateTime.of(2026, 11, 2, 14, 0);

    @Autowired
    private IncidentProcessingService incidentProcessingService;

    @Autowired
    private ExaminationService examinationService;

    // 실패 주입 테스트에서만 일부 메서드를 바꾸고, 나머지 테스트에서는 실제 Repository처럼 동작한다
    @MockitoSpyBean
    private IncidentReferralRepository incidentReferralRepository;

    @Autowired
    private IncidentAffectedReferralRepository incidentAffectedReferralRepository;

    @Autowired
    private EquipmentIncidentRepository equipmentIncidentRepository;

    @Autowired
    private ReferralRepository referralRepository;

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private ExaminationRepository examinationRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    // 현재 트랜잭션에 묶이는 공유 EntityManager (실패 주입 시 flush 용도)
    @Autowired
    private EntityManager entityManager;

    private final UUID equipmentId = UUID.randomUUID();
    private final List<UUID> referralIds = new ArrayList<>();
    private final List<UUID> reservationIds = new ArrayList<>();
    private final List<UUID> incidentIds = new ArrayList<>();
    private final List<UUID> examinationIds = new ArrayList<>();

    private record Booking(UUID referralId, UUID reservationId, UUID examInstitutionId) {
    }

    @AfterEach
    void cleanUp() {
        examinationRepository.deleteAllById(examinationIds);
        incidentReferralRepository.deleteAll(incidentReferralRepository.findAll().stream()
                .filter(task -> incidentIds.contains(task.getIncident().getId()))
                .toList());
        equipmentIncidentRepository.deleteAllById(incidentIds);
        reservationIds.forEach(id -> reservationRepository.findById(id).ifPresent(reservationRepository::delete));
        referralIds.forEach(id -> referralRepository.findById(id).ifPresent(referralRepository::delete));
    }

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }

    // 운영의 예약 확정 Saga 대신 도메인 메서드로 CONFIRMED 의뢰 + 현재 예약(CONFIRMED)을 만든다
    private Booking confirmedBooking() {
        Booking booking = tx().execute(status -> {
            Referral referral = referralRepository.save(Referral.create(UUID.randomUUID(), UUID.randomUUID(),
                    UUID.randomUUID(), "가상환자", UUID.randomUUID()));
            UUID examInstitutionId = UUID.randomUUID();
            Reservation reservation = Reservation.create(referral, 1, UUID.randomUUID(), examInstitutionId, equipmentId,
                    EXAM_START, EXAM_START, EXAM_START.plusHours(1));
            reservation.hold();
            reservation.confirm();
            reservationRepository.save(reservation);
            referral.assignReservation(reservation.getId());
            referral.confirm();
            return new Booking(referral.getId(), reservation.getId(), examInstitutionId);
        });
        referralIds.add(booking.referralId());
        reservationIds.add(booking.reservationId());
        return booking;
    }

    // 장애 1건과 그 장애의 PENDING 작업 1건을 만든다 (2단계에서 생성되는 작업과 같은 형태)
    private UUID pendingTask(UUID referralId, UUID reservationId) {
        return tx().execute(status -> {
            EquipmentIncident incident = equipmentIncidentRepository.save(EquipmentIncident.occur(UUID.randomUUID(),
                    equipmentId, UUID.randomUUID(), "가상 장애 사유", EXAM_START, EXAM_START, EXAM_START.plusHours(3)));
            incidentIds.add(incident.getId());
            return incidentReferralRepository.save(IncidentReferral.pending(incident, referralId, reservationId))
                    .getId();
        });
    }

    private UUID pendingTask(Booking booking) {
        return pendingTask(booking.referralId(), booking.reservationId());
    }

    private Referral referral(UUID id) {
        return referralRepository.findById(id).orElseThrow();
    }

    private Reservation reservation(UUID id) {
        return reservationRepository.findById(id).orElseThrow();
    }

    private IncidentReferral task(UUID id) {
        return incidentReferralRepository.findById(id).orElseThrow();
    }

    private void assertUnchanged(Booking booking) {
        assertThat(referral(booking.referralId()).getReferralStatus()).isEqualTo(ReferralStatus.CONFIRMED);
        assertThat(reservation(booking.reservationId()).getReservationStatus()).isEqualTo(ReservationStatus.CONFIRMED);
    }

    @Test
    @DisplayName("정상 처리: 예약 BLOCKED, 의뢰 NEEDS_RESCHEDULE, 작업 SUCCEEDED(RESCHEDULE_REQUIRED)")
    void processConfirmedBooking() {
        Booking booking = confirmedBooking();
        UUID taskId = pendingTask(booking);

        Outcome outcome = incidentProcessingService.process(taskId);

        assertThat(outcome).isEqualTo(Outcome.SUCCEEDED);
        Reservation blocked = reservation(booking.reservationId());
        assertThat(blocked.getReservationStatus()).isEqualTo(ReservationStatus.BLOCKED);
        assertThat(blocked.getFailureReason()).isEqualTo(FailureReason.EQUIPMENT_FAILURE);
        assertThat(referral(booking.referralId()).getReferralStatus()).isEqualTo(ReferralStatus.NEEDS_RESCHEDULE);
        IncidentReferral done = task(taskId);
        assertThat(done.getProcessingStatus()).isEqualTo(IncidentProcessingStatus.SUCCEEDED);
        assertThat(done.getResultCode()).isEqualTo(IncidentProcessingService.RESCHEDULE_REQUIRED);
        assertThat(done.getProcessedAt()).isNotNull();
        assertThat(done.getLockedUntil()).isNull();
    }

    @Test
    @DisplayName("이미 SUCCEEDED인 작업을 다시 실행하면 선점하지 못하고 아무것도 바뀌지 않는다")
    void succeededTaskIsNotReprocessed() {
        Booking booking = confirmedBooking();
        UUID taskId = pendingTask(booking);
        incidentProcessingService.process(taskId);
        LocalDateTime processedAt = task(taskId).getProcessedAt();

        assertThat(incidentProcessingService.process(taskId)).isEqualTo(Outcome.NOT_CLAIMED);
        assertThat(task(taskId).getProcessedAt()).isEqualTo(processedAt);
    }

    @Test
    @DisplayName("같은 작업을 10개 스레드가 동시에 실행해도 1개만 처리하고 나머지는 NOT_CLAIMED")
    void concurrentSameTask() throws Exception {
        Booking booking = confirmedBooking();
        UUID taskId = pendingTask(booking);

        List<Outcome> outcomes = runConcurrently(10, () -> incidentProcessingService.process(taskId));

        assertThat(outcomes).filteredOn(outcome -> outcome == Outcome.SUCCEEDED).hasSize(1);
        assertThat(outcomes).filteredOn(outcome -> outcome == Outcome.NOT_CLAIMED).hasSize(9);
        assertThat(reservation(booking.reservationId()).getReservationStatus()).isEqualTo(ReservationStatus.BLOCKED);
        assertThat(task(taskId).getResultCode()).isEqualTo(IncidentProcessingService.RESCHEDULE_REQUIRED);
    }

    @Test
    @DisplayName("같은 예약에 대한 서로 다른 장애 작업 2건이 동시에 실행돼도 변경은 1번이고 다른 하나는 ALREADY_BLOCKED")
    void concurrentTasksOfSameReservation() throws Exception {
        Booking booking = confirmedBooking();
        UUID first = pendingTask(booking);
        UUID second = pendingTask(booking);

        runConcurrently(2, List.of(first, second), incidentProcessingService::process);

        assertThat(List.of(task(first).getResultCode(), task(second).getResultCode())).containsExactlyInAnyOrder(
                IncidentProcessingService.RESCHEDULE_REQUIRED, IncidentProcessingService.ALREADY_BLOCKED);
        assertThat(referral(booking.referralId()).getReferralStatus()).isEqualTo(ReferralStatus.NEEDS_RESCHEDULE);
    }

    @Test
    @DisplayName("다른 장애로 이미 차단된 예약은 다시 바꾸지 않고 ALREADY_BLOCKED (차단 시각 유지)")
    void alreadyBlockedByOtherIncident() {
        Booking booking = confirmedBooking();
        incidentProcessingService.process(pendingTask(booking));
        LocalDateTime endedAt = reservation(booking.reservationId()).getEndedAt();
        UUID secondTask = pendingTask(booking);

        assertThat(incidentProcessingService.process(secondTask)).isEqualTo(Outcome.SUCCEEDED);
        assertThat(task(secondTask).getResultCode()).isEqualTo(IncidentProcessingService.ALREADY_BLOCKED);
        assertThat(reservation(booking.reservationId()).getEndedAt()).isEqualTo(endedAt);
    }

    @Test
    @DisplayName("작업 처리 전에 재예약이 끝났다면 이전 예약·새 예약·의뢰를 바꾸지 않고 SKIPPED_NOT_CURRENT")
    void rescheduledBeforeProcessing() {
        Booking booking = confirmedBooking();
        UUID taskId = pendingTask(booking);
        // 다른 경로로 재조정 필요가 된 뒤 담당자가 새 슬롯으로 재예약했다고 가정한다
        UUID newReservationId = tx().execute(status -> {
            Referral referral = referral(booking.referralId());
            referral.requestReschedule();
            Reservation newReservation = reservationRepository.save(Reservation.create(referral, 2, UUID.randomUUID(),
                    UUID.randomUUID(), UUID.randomUUID(), EXAM_START.plusDays(1), EXAM_START.plusDays(1),
                    EXAM_START.plusDays(1).plusHours(1)));
            referral.reschedule(newReservation.getId());
            referralRepository.save(referral);
            return newReservation.getId();
        });
        reservationIds.add(newReservationId);

        assertThat(incidentProcessingService.process(taskId)).isEqualTo(Outcome.SUCCEEDED);
        assertThat(task(taskId).getResultCode()).isEqualTo(IncidentProcessingService.SKIPPED_NOT_CURRENT);
        assertThat(reservation(booking.reservationId()).getReservationStatus()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(reservation(newReservationId).getReservationStatus()).isEqualTo(ReservationStatus.REQUESTED);
        Referral referral = referral(booking.referralId());
        assertThat(referral.getReferralStatus()).isEqualTo(ReferralStatus.PENDING);
        assertThat(referral.getCurrentReservationId()).isEqualTo(newReservationId);
    }

    @Test
    @DisplayName("작업 처리 전에 예약이 취소됐다면 취소 상태를 덮어쓰지 않고 SKIPPED_CANCELED")
    void canceledBeforeProcessing() {
        Booking booking = confirmedBooking();
        UUID taskId = pendingTask(booking);
        tx().executeWithoutResult(status -> {
            Reservation reservation = reservation(booking.reservationId());
            reservation.cancel();
            reservationRepository.save(reservation);
        });

        assertThat(incidentProcessingService.process(taskId)).isEqualTo(Outcome.SUCCEEDED);
        assertThat(task(taskId).getResultCode()).isEqualTo(IncidentProcessingService.SKIPPED_CANCELED);
        assertThat(reservation(booking.reservationId()).getReservationStatus()).isEqualTo(ReservationStatus.CANCELED);
        assertThat(referral(booking.referralId()).getReferralStatus()).isEqualTo(ReferralStatus.CONFIRMED);
    }

    @Test
    @DisplayName("작업의 의뢰와 예약의 실제 의뢰가 다르면 두 예약 모두 바꾸지 않고 DATA_INCONSISTENCY로 FAILED")
    void mismatchedReferral() {
        Booking booking = confirmedBooking();
        Booking other = confirmedBooking();
        UUID taskId = pendingTask(other.referralId(), booking.reservationId());

        assertThat(incidentProcessingService.process(taskId)).isEqualTo(Outcome.FAILED);
        IncidentReferral failed = task(taskId);
        assertThat(failed.getProcessingStatus()).isEqualTo(IncidentProcessingStatus.FAILED);
        assertThat(failed.getLastError()).startsWith(IncidentProcessingService.DATA_INCONSISTENCY);
        assertUnchanged(booking);
        assertUnchanged(other);
    }

    @Test
    @DisplayName("예약·의뢰 변경이 DB에 반영된 뒤 성공 기록 단계에서 오류가 나면 모두 롤백되고, FAILED·원인·다음 재시도 시각은 별도 트랜잭션으로 남는다")
    void failureRollsBackAndIsRecorded() {
        Booking booking = confirmedBooking();
        UUID taskId = pendingTask(booking);
        // 예약 BLOCKED·의뢰 NEEDS_RESCHEDULE UPDATE를 실제로 DB에 보낸(flush) 뒤 같은 트랜잭션에서 오류가 난 상황을 만든다
        doAnswer(invocation -> {
            entityManager.flush();
            throw new IllegalStateException("주입된 오류");
        }).when(incidentReferralRepository).markSucceeded(any(), any(), any(), any());

        assertThat(incidentProcessingService.process(taskId)).isEqualTo(Outcome.FAILED);

        assertUnchanged(booking);
        IncidentReferral failed = task(taskId);
        assertThat(failed.getProcessingStatus()).isEqualTo(IncidentProcessingStatus.FAILED);
        assertThat(failed.getLastError()).isEqualTo(IncidentProcessingService.RETRYABLE + " IllegalStateException")
                .doesNotContain("주입된 오류");
        assertThat(failed.getNextRetryAt()).isNotNull();
        assertThat(failed.getLockedUntil()).isNull();
        assertThat(failed.getResultCode()).isNull();
    }

    @Test
    @DisplayName("환자 이름이 담긴 DB 제약 위반이 나도 last_error·로그에는 개인정보가 남지 않고 FAILED·재시도 정보는 저장된다")
    void personalDataIsNotStoredOrLogged(CapturedOutput output) {
        Booking booking = confirmedBooking();
        UUID taskId = pendingTask(booking);
        String patientName = "가상환자";   // confirmedBooking()이 의뢰에 저장한 가상 환자 이름
        String failingRow = "ERROR: null value in column \"referral_status\" of relation \"p_referrals\" violates "
                + "not-null constraint\n  Detail: Failing row contains (" + booking.referralId() + ", " + patientName
                + ", null, ...)";
        doAnswer(invocation -> {
            entityManager.flush();
            throw new DataIntegrityViolationException("could not execute statement [" + failingRow + "]",
                    new SQLException(failingRow, "23502"));
        }).when(incidentReferralRepository).markSucceeded(any(), any(), any(), any());

        assertThat(incidentProcessingService.process(taskId)).isEqualTo(Outcome.FAILED);

        assertUnchanged(booking);
        IncidentReferral failed = task(taskId);
        assertThat(failed.getProcessingStatus()).isEqualTo(IncidentProcessingStatus.FAILED);
        assertThat(failed.getNextRetryAt()).isNotNull();
        assertThat(failed.getLockedUntil()).isNull();
        assertThat(failed.getLastError())
                .isEqualTo(IncidentProcessingService.RETRYABLE + " DataIntegrityViolationException sqlState=23502")
                .doesNotContain(patientName, "Failing row");
        assertThat(output.getOut() + output.getErr())
                .contains("장애 후속 처리 실패", taskId.toString())
                .doesNotContain(patientName, "Failing row");
    }

    @Test
    @DisplayName("FAILED 작업은 다음 재시도 시각 이후 다시 선점되어 처리된다 (retryCount 1)")
    void failedTaskIsRetried() {
        Booking booking = confirmedBooking();
        UUID taskId = pendingTask(booking);
        doAnswer(invocation -> {
            throw new IllegalStateException("일시 오류");
        }).when(incidentReferralRepository).markSucceeded(any(), any(), any(), any());
        incidentProcessingService.process(taskId);
        reset(incidentReferralRepository);
        LocalDateTime afterRetryTime = task(taskId).getNextRetryAt().plusSeconds(1);

        assertThat(incidentProcessingService.process(taskId, afterRetryTime)).isEqualTo(Outcome.SUCCEEDED);
        IncidentReferral done = task(taskId);
        assertThat(done.getResultCode()).isEqualTo(IncidentProcessingService.RESCHEDULE_REQUIRED);
        assertThat(done.getRetryCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("처리 중 잠금이 만료돼 다른 작업자가 재선점하면, 이전 작업자의 예약·의뢰 변경은 롤백되고 기록도 하지 않는다")
    void leaseLostDuringProcessing() throws Exception {
        Booking booking = confirmedBooking();
        UUID taskId = pendingTask(booking);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch referralLocked = new CountDownLatch(1);
        CountDownLatch releaseLock = new CountDownLatch(1);

        try {
            // 다른 트랜잭션이 의뢰 행을 잠그고 있어 작업자 A가 처리 단계(T2)에서 기다리게 만든다
            Future<?> holder = executor.submit(() -> tx().executeWithoutResult(status -> {
                incidentAffectedReferralRepository.findByIdForUpdate(booking.referralId());
                referralLocked.countDown();
                await(releaseLock);
            }));
            await(referralLocked);

            LocalDateTime t0 = LocalDateTime.now();
            LocalDateTime leaseOfA = t0.truncatedTo(ChronoUnit.MICROS).plusMinutes(5);
            Future<Outcome> workerA = executor.submit(() -> incidentProcessingService.process(taskId, t0));
            waitUntil(() -> leaseOfA.equals(task(taskId).getLockedUntil()));   // A의 선점(T1)이 커밋됨

            // A의 잠금이 만료된 시점에 작업자 B가 재선점한다
            LocalDateTime leaseOfB = leaseOfA.plusMinutes(6);
            assertThat(incidentReferralRepository.claim(taskId, leaseOfA.plusMinutes(1), leaseOfB)).isEqualTo(1);

            releaseLock.countDown();
            holder.get(10, TimeUnit.SECONDS);

            assertThat(workerA.get(10, TimeUnit.SECONDS)).isEqualTo(Outcome.LEASE_LOST);
            assertUnchanged(booking);
            IncidentReferral afterA = task(taskId);
            assertThat(afterA.getProcessingStatus()).isEqualTo(IncidentProcessingStatus.PROCESSING);
            assertThat(afterA.getLockedUntil()).isEqualTo(leaseOfB);
            assertThat(afterA.getLastError()).isNull();
        } finally {
            releaseLock.countDown();
            executor.shutdown();
        }
    }

    @Test
    @DisplayName("선점 후 서버가 멈춰 PROCESSING으로 남은 작업은 잠금 만료 뒤 재처리 대상이 되고 정상 처리된다")
    void recoveryAfterCrash() {
        Booking booking = confirmedBooking();
        UUID taskId = pendingTask(booking);
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        // 10분 전에 선점하고 5분 전에 잠금이 만료된 채 멈춘 작업자
        incidentReferralRepository.claim(taskId, now.minusMinutes(10), now.minusMinutes(5));
        UUID incidentId = task(taskId).getIncident().getId();

        assertThat(incidentReferralRepository.findRetryTargets(incidentId, now, PageRequest.of(0, 10)))
                .extracting(IncidentReferral::getId).containsExactly(taskId);
        assertThat(incidentProcessingService.process(taskId)).isEqualTo(Outcome.SUCCEEDED);
        assertThat(reservation(booking.reservationId()).getReservationStatus()).isEqualTo(ReservationStatus.BLOCKED);
        assertThat(task(taskId).getResultCode()).isEqualTo(IncidentProcessingService.RESCHEDULE_REQUIRED);
    }

    @Test
    @DisplayName("장애 처리 후에는 해당 의뢰의 검사를 시작할 수 없다 (EXAMINATION_START_NOT_ALLOWED)")
    void examinationStartIsBlockedAfterProcessing() {
        Booking booking = confirmedBooking();
        UUID examinationId = examinationService.createScheduled(booking.referralId(), booking.reservationId(),
                booking.examInstitutionId());
        examinationIds.add(examinationId);
        UUID taskId = pendingTask(booking);

        incidentProcessingService.process(taskId);

        assertThatThrownBy(() -> examinationService.changeStatus(examinationId, ExaminationStatus.IN_PROGRESS))
                .isInstanceOf(BaseException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.EXAMINATION_START_NOT_ALLOWED);
        assertThat(examinationRepository.findById(examinationId).orElseThrow().getStatus())
                .isEqualTo(ExaminationStatus.SCHEDULED);
    }

    @Test
    @DisplayName("[알려진 위험] 잠금 없이 예약을 먼저 읽은 취소 처리가 장애 처리 뒤에 커밋되면 BLOCKED를 CANCELED로 덮어쓴다")
    void knownRiskCancelOverwritesBlocked() throws Exception {
        Booking booking = confirmedBooking();
        UUID taskId = pendingTask(booking);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch cancelHasRead = new CountDownLatch(1);
        CountDownLatch commitCancel = new CountDownLatch(1);

        try {
            // 예약 취소 코드(ReservationService.cancelReservation)처럼 잠금·버전 없이 읽고, 나중에 cancel()로 저장한다
            Future<?> cancel = executor.submit(() -> tx().executeWithoutResult(status -> {
                Reservation stale = reservationRepository.findById(booking.reservationId()).orElseThrow();
                cancelHasRead.countDown();
                await(commitCancel);
                stale.cancel();
            }));
            await(cancelHasRead);

            assertThat(incidentProcessingService.process(taskId)).isEqualTo(Outcome.SUCCEEDED);
            commitCancel.countDown();
            cancel.get(10, TimeUnit.SECONDS);
        } finally {
            commitCancel.countDown();
            executor.shutdown();
        }

        // 해결되지 않은 경합의 실제 결과: 예약은 CANCELED, 의뢰는 NEEDS_RESCHEDULE, 작업은 차단 성공으로 남는다
        assertThat(reservation(booking.reservationId()).getReservationStatus()).isEqualTo(ReservationStatus.CANCELED);
        assertThat(referral(booking.referralId()).getReferralStatus()).isEqualTo(ReferralStatus.NEEDS_RESCHEDULE);
        assertThat(task(taskId).getResultCode()).isEqualTo(IncidentProcessingService.RESCHEDULE_REQUIRED);
    }

    // 같은 작업을 threadCount개 스레드에서 동시에 실행한다
    private <T> List<T> runConcurrently(int threadCount, java.util.concurrent.Callable<T> action) throws Exception {
        return runConcurrently(threadCount, java.util.Collections.nCopies(threadCount, (UUID) null), ignored -> action.call());
    }

    private <T> List<T> runConcurrently(int threadCount, List<UUID> inputs, ThrowingFunction<T> action)
            throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (UUID input : inputs) {
                futures.add(executor.submit(() -> {
                    ready.await();
                    return action.apply(input);
                }));
            }
            ready.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            executor.shutdown();
        }
    }

    @FunctionalInterface
    private interface ThrowingFunction<T> {
        T apply(UUID input) throws Exception;
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("대기 시간 초과");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static void waitUntil(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new IllegalStateException("조건 대기 시간 초과");
            }
            Thread.sleep(20);
        }
    }
}
