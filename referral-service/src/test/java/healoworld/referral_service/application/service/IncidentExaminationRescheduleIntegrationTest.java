package healoworld.referral_service.application.service;

import healoworld.referral_service.application.event.result.SlotResultType;
import healoworld.referral_service.application.service.IncidentProcessingService.Outcome;
import healoworld.referral_service.domain.entity.Examination;
import healoworld.referral_service.domain.entity.IncidentReferral;
import healoworld.referral_service.domain.entity.Referral;
import healoworld.referral_service.domain.entity.ReferralHistory;
import healoworld.referral_service.domain.entity.Reservation;
import healoworld.referral_service.domain.enums.ExaminationStatus;
import healoworld.referral_service.domain.enums.IncidentProcessingStatus;
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
import healoworld.referral_service.infrastructure.client.institution.InstitutionClient;
import healoworld.referral_service.infrastructure.client.institution.PartnershipCheckResponse;
import healoworld.referral_service.infrastructure.repository.EquipmentIncidentLockRepository;
import healoworld.referral_service.infrastructure.repository.EquipmentIncidentRepository;
import healoworld.referral_service.infrastructure.repository.ExaminationRepository;
import healoworld.referral_service.infrastructure.repository.IncidentReferralRepository;
import healoworld.referral_service.infrastructure.repository.NotificationRepository;
import healoworld.referral_service.infrastructure.repository.ReferralHistoryRepository;
import healoworld.referral_service.infrastructure.repository.ReferralRepository;
import healoworld.referral_service.infrastructure.repository.ReservationRepository;
import healoworld.referral_service.presentation.dto.request.CreateReservationRequest;
import healoworld.referral_service.presentation.dto.request.IncidentReferralSearchCondition;
import healoworld.referral_service.presentation.dto.response.IncidentReferralResponse;
import healoworld.referral_service.presentation.dto.response.ReservationResponse;
import healoworld.referral_service.support.PostgresContainerSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.mockito.Mockito;
import org.mockito.stubbing.Answer;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doAnswer;

/**
 * 검사 상태별 장비 장애 처리와 재예약을 실제 PostgreSQL(Testcontainers)에서 검증한다.
 * <p>
 * - 예약 확정(SlotBooked) → 장애 등록 → 후속 처리 → 재예약 → 새 예약 확정까지 실제 서비스를 호출한다 (Kafka·Slot Service는 거치지 않음)
 * - 검사 상태는 검사 Repository의 조건부 UPDATE로 바꿔 검사기관의 검사 시작·완료를 재현한다
 * - 각 작업이 실제로 커밋하므로 테스트 트랜잭션을 끄고, 만든 행만 끝날 때 지운다
 */
@DataJpaTest
@Import({
        ReferralService.class,
        SlotResultService.class,
        EquipmentIncidentService.class,
        EquipmentIncidentLockRepository.class,
        IncidentProcessingService.class,
        NotificationService.class,
        ExaminationService.class,
        ReservationService.class,
        ReferralHistoryService.class,
        SlotCommandOutbox.class,
        EquipmentIncidentQueryService.class,
        QuerydslConfig.class, ClockConfig.class,
        IncidentExaminationRescheduleIntegrationTest.JsonConfig.class
})
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class IncidentExaminationRescheduleIntegrationTest extends PostgresContainerSupport {

    @TestConfiguration(proxyBeanMethods = false)
    static class JsonConfig {
        @Bean
        JsonMapper jsonMapper() {
            return JsonMapper.builder().build();
        }
    }

    @Autowired private ReferralService referralService;
    @Autowired private SlotResultService slotResultService;
    @Autowired private EquipmentIncidentService equipmentIncidentService;
    @Autowired private IncidentProcessingService incidentProcessingService;
    @Autowired private ReferralRepository referralRepository;
    @Autowired private ReservationRepository reservationRepository;
    // 동시성 테스트에서 검사 시작·장애 처리를 원하는 지점에서 멈추기 위해 스파이로 둔다 (기본은 실제 Repository를 그대로 호출)
    @MockitoSpyBean private ExaminationRepository examinationRepository;
    @Autowired private ExaminationService examinationService;
    @Autowired private EquipmentIncidentQueryService equipmentIncidentQueryService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private EquipmentIncidentRepository equipmentIncidentRepository;
    @Autowired private IncidentReferralRepository incidentReferralRepository;
    @Autowired private ReferralHistoryRepository referralHistoryRepository;
    @Autowired private NotificationRepository notificationRepository;
    @Autowired private OutboxEventRepository outboxEventRepository;
    @Autowired private ConsumedEventRepository consumedEventRepository;
    @Autowired private PlatformTransactionManager transactionManager;

    // 재예약 시 협력 관계 확인 (institution-service)
    @MockitoBean private InstitutionClient institutionClient;

    private final UUID userId = UUID.randomUUID();
    private final UUID institutionId = UUID.randomUUID();
    private final UUID equipmentId = UUID.randomUUID();
    private final LocalDateTime startsAt =
            LocalDateTime.now().plusDays(3).withHour(14).withMinute(0).withSecond(0).withNano(0);

    private UUID referralId;
    private UUID firstReservationId;
    private final List<UUID> incidentIds = new ArrayList<>();
    private final List<UUID> eventIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        given(institutionClient.check(any(), any())).willReturn(new PartnershipCheckResponse(true));
        // 첫 예약: HOLD까지 끝난 상태 (의뢰 PENDING, 예약 HELD)
        tx().executeWithoutResult(status -> {
            Referral referral = referralRepository.save(Referral.create(institutionId, userId, UUID.randomUUID(),
                    "가상환자", UUID.randomUUID()));
            Reservation reservation = Reservation.create(referral, 1, UUID.randomUUID(), UUID.randomUUID(),
                    equipmentId, startsAt, startsAt, startsAt.plusHours(1));
            reservation.hold();
            reservationRepository.save(reservation);
            referral.assignReservation(reservation.getId());
            referralId = referral.getId();
            firstReservationId = reservation.getId();
        });
        // 확정 → 검사 SCHEDULED 생성
        slotResult(SlotResultType.SLOT_BOOKED, firstReservationId, UUID.randomUUID());
    }

    @AfterEach
    void cleanUp() {
        List<UUID> reservationIds = reservations().stream().map(Reservation::getId).toList();
        notificationRepository.deleteAll(notificationRepository.findAll().stream()
                .filter(n -> referralId.equals(n.getReferralId())).toList());
        referralHistoryRepository.deleteAll(referralHistoryRepository.findAllByReferralIdOrderByOccurredAtAsc(referralId));
        outboxEventRepository.deleteAll(outboxEventRepository.findAll().stream()
                .filter(e -> reservationIds.contains(e.getAggregateId())).toList());
        eventIds.forEach(id -> consumedEventRepository.deleteById(new ConsumedEventId(id, SlotResultService.CONSUMER)));
        incidentReferralRepository.deleteAll(incidentReferralRepository.findAll().stream()
                .filter(t -> incidentIds.contains(t.getIncident().getId())).toList());
        equipmentIncidentRepository.deleteAllById(incidentIds);
        examinationRepository.findByReferralId(referralId).ifPresent(examinationRepository::delete);
        reservationRepository.deleteAllById(reservationIds);
        referralRepository.deleteById(referralId);
    }

    // ---------- 1. SCHEDULED: 장애 차단 → 재예약 → 새 예약 확정 ----------

    @Test
    @DisplayName("SCHEDULED 검사: 장애로 차단·재조정되고 이력이 남으며, 재예약 확정 시 검사가 새 예약으로 옮겨진다")
    void scheduledExaminationRescheduled() {
        IncidentReferral task = blockByIncident();
        assertThat(resultCode(task)).isEqualTo(IncidentProcessingService.RESCHEDULE_REQUIRED);
        assertThat(reservationStatus(firstReservationId)).isEqualTo(ReservationStatus.BLOCKED);
        assertThat(referralStatus()).isEqualTo(ReferralStatus.NEEDS_RESCHEDULE);
        assertThat(notificationCount()).isEqualTo(1);
        // 보완한 이력: CONFIRMED → NEEDS_RESCHEDULE (RESERVATION_BLOCKED)
        assertThat(histories()).anySatisfy(h -> {
            assertThat(h.getFromStatus()).isEqualTo(ReferralStatus.CONFIRMED);
            assertThat(h.getToStatus()).isEqualTo(ReferralStatus.NEEDS_RESCHEDULE);
            assertThat(h.getReason()).isEqualTo(Reason.RESERVATION_BLOCKED);
        });

        UUID newReservationId = reschedule().getId();
        assertThat(referralStatus()).isEqualTo(ReferralStatus.PENDING);

        slotResult(SlotResultType.SLOT_HELD, newReservationId, UUID.randomUUID());
        UUID bookedEventId = UUID.randomUUID();
        slotResult(SlotResultType.SLOT_BOOKED, newReservationId, bookedEventId);

        assertThat(reservationStatus(newReservationId)).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(referralStatus()).isEqualTo(ReferralStatus.CONFIRMED);
        Examination examination = examinationRepository.findByReferralId(referralId).orElseThrow();
        assertThat(examination.getStatus()).isEqualTo(ExaminationStatus.SCHEDULED);
        assertThat(examination.getReservationId()).isEqualTo(newReservationId);

        // 7. 같은 SlotBooked가 다시 와도(재전달) 예외 없이 건너뛰고 상태가 그대로다
        long historyCount = histories().size();
        slotResult(SlotResultType.SLOT_BOOKED, newReservationId, bookedEventId);
        slotResult(SlotResultType.SLOT_BOOKED, newReservationId, UUID.randomUUID());
        assertThat(reservationStatus(newReservationId)).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(examinationRepository.findByReferralId(referralId).orElseThrow().getReservationId())
                .isEqualTo(newReservationId);
        assertThat(histories()).hasSize((int) historyCount);
    }

    // ---------- 2·3. IN_PROGRESS·COMPLETED: 장애 처리 제외 ----------

    @ParameterizedTest
    @EnumSource(value = ExaminationStatus.class, names = {"IN_PROGRESS", "COMPLETED"})
    @DisplayName("검사가 시작·완료된 뒤 장애가 나면 예약·의뢰·검사를 바꾸지 않고 제외하며 알림·이력도 없다")
    void startedExaminationIsExcluded(ExaminationStatus examinationStatus) {
        moveExaminationTo(examinationStatus);
        int historyCount = histories().size();

        IncidentReferral task = blockByIncident();

        assertThat(resultCode(task)).isEqualTo(examinationStatus == ExaminationStatus.IN_PROGRESS
                ? IncidentProcessingService.SKIPPED_IN_PROGRESS : IncidentProcessingService.SKIPPED_COMPLETED);
        assertThat(reservationStatus(firstReservationId)).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(referralStatus()).isEqualTo(ReferralStatus.CONFIRMED);
        assertThat(examinationRepository.findByReferralId(referralId).orElseThrow().getStatus())
                .isEqualTo(examinationStatus);
        assertThat(notificationCount()).isZero();
        assertThat(histories()).hasSize(historyCount);
        // 같은 작업을 다시 처리하려 해도 이미 끝난 작업이라 선점되지 않는다 (멱등)
        assertThat(incidentProcessingService.process(task.getId())).isEqualTo(Outcome.NOT_CLAIMED);
    }

    // ---------- 영향 의뢰 조회: 검사 진행 중 장애의 수동 확인 표시 (manualReviewRequired) ----------
    // 실제 장애 처리 결과(작업 SUCCEEDED + 결과 코드)와 조회 시점의 검사 상태로 계산되는지 확인한다

    @Test
    @DisplayName("수동 확인: SCHEDULED 검사는 기존처럼 차단·재조정되고 manualReviewRequired는 false")
    void manualReviewFalseWhenScheduled() {
        IncidentReferral task = blockByIncident();

        IncidentReferralResponse item = affectedReferral(RoleType.ADMIN, UUID.randomUUID());

        assertThat(item.id()).isEqualTo(task.getId());
        assertThat(item.resultCode()).isEqualTo(IncidentProcessingService.RESCHEDULE_REQUIRED);
        assertThat(reservationStatus(firstReservationId)).isEqualTo(ReservationStatus.BLOCKED);
        assertThat(referralStatus()).isEqualTo(ReferralStatus.NEEDS_RESCHEDULE);
        assertThat(item.manualReviewRequired()).isFalse();
    }

    @Test
    @DisplayName("수동 확인: 검사 진행 중 장애는 상태를 바꾸지 않고 true, 검사가 COMPLETED가 되면 다시 조회할 때 false")
    void manualReviewWhileInProgressThenCompleted() {
        moveExaminationTo(ExaminationStatus.IN_PROGRESS);
        IncidentReferral task = blockByIncident();
        UUID incidentInstitutionId = incidentInstitutionId();

        IncidentReferralResponse forExaminer = affectedReferral(RoleType.EXAMINER, incidentInstitutionId);
        IncidentReferralResponse forAdmin = affectedReferral(RoleType.ADMIN, UUID.randomUUID());

        // 자동 처리 작업은 끝났지만(SUCCEEDED) 담당자 확인이 필요하다
        assertThat(forExaminer.processingStatus()).isEqualTo(IncidentProcessingStatus.SUCCEEDED);
        assertThat(forExaminer.resultCode()).isEqualTo(IncidentProcessingService.SKIPPED_IN_PROGRESS);
        assertThat(forExaminer.manualReviewRequired()).isTrue();
        assertThat(forAdmin.manualReviewRequired()).isTrue();
        // 예약·의뢰·검사 상태는 그대로이고 재조정 알림도 없다
        assertThat(reservationStatus(firstReservationId)).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(referralStatus()).isEqualTo(ReferralStatus.CONFIRMED);
        assertThat(examinationStatus()).isEqualTo(ExaminationStatus.IN_PROGRESS);
        assertThat(notificationCount()).isZero();

        // 검사기관이 검사를 완료하면 작업 데이터는 그대로지만 수동 확인 대상에서 빠진다
        Examination examination = examinationRepository.findByReferralId(referralId).orElseThrow();
        assertThat(examinationRepository.completeIfInProgress(examination.getId(), LocalDateTime.now())).isEqualTo(1);

        IncidentReferralResponse afterCompleted = affectedReferral(RoleType.EXAMINER, incidentInstitutionId);
        assertThat(afterCompleted.id()).isEqualTo(task.getId());
        assertThat(afterCompleted.processingStatus()).isEqualTo(IncidentProcessingStatus.SUCCEEDED);
        assertThat(afterCompleted.resultCode()).isEqualTo(IncidentProcessingService.SKIPPED_IN_PROGRESS);
        assertThat(afterCompleted.manualReviewRequired()).isFalse();
    }

    @Test
    @DisplayName("수동 확인: 이미 COMPLETED인 검사는 SKIPPED_COMPLETED이고 false")
    void manualReviewFalseWhenCompleted() {
        moveExaminationTo(ExaminationStatus.COMPLETED);
        blockByIncident();

        IncidentReferralResponse item = affectedReferral(RoleType.EXAMINER, incidentInstitutionId());

        assertThat(item.resultCode()).isEqualTo(IncidentProcessingService.SKIPPED_COMPLETED);
        assertThat(item.manualReviewRequired()).isFalse();
    }

    @Test
    @DisplayName("수동 확인: 같은 장애 이벤트가 다시 와도 작업은 1건이고 예약은 차단되지 않으며 true가 한 번만 표시된다")
    void manualReviewWithDuplicateIncidentEvent() {
        moveExaminationTo(ExaminationStatus.IN_PROGRESS);
        UUID sourceEventId = UUID.randomUUID();
        UUID ownerInstitutionId = UUID.randomUUID();
        LocalDateTime occurredAt = LocalDateTime.now();
        UUID incidentId = equipmentIncidentService.register(sourceEventId, equipmentId, ownerInstitutionId,
                "가상 장애 사유", occurredAt, startsAt.minusHours(1), startsAt.plusHours(2));
        incidentIds.add(incidentId);
        assertThat(equipmentIncidentService.createPendingReferrals(incidentId)).isEqualTo(1);
        // 같은 이벤트 재전달: 같은 장애 ID를 돌려받고 새 작업은 만들지 않는다
        assertThat(equipmentIncidentService.register(sourceEventId, equipmentId, ownerInstitutionId,
                "가상 장애 사유", occurredAt, startsAt.minusHours(1), startsAt.plusHours(2))).isEqualTo(incidentId);
        assertThat(equipmentIncidentService.createPendingReferrals(incidentId)).isZero();
        UUID taskId = incidentReferralRepository.findAll().stream()
                .filter(t -> t.getIncident().getId().equals(incidentId)).findFirst().orElseThrow().getId();
        assertThat(incidentProcessingService.process(taskId)).isEqualTo(Outcome.SUCCEEDED);

        var page = equipmentIncidentQueryService.getIncidentReferrals(RoleType.EXAMINER, ownerInstitutionId,
                incidentId, new IncidentReferralSearchCondition(null, 0, 10));

        assertThat(page.contents()).singleElement().satisfies(item -> {
            assertThat(item.resultCode()).isEqualTo(IncidentProcessingService.SKIPPED_IN_PROGRESS);
            assertThat(item.manualReviewRequired()).isTrue();
        });
        assertThat(reservationStatus(firstReservationId)).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(blockedHistoryCount()).isZero();
    }

    // 가장 최근 장애의 영향 의뢰 목록에서 이 의뢰의 항목 하나를 조회한다
    private IncidentReferralResponse affectedReferral(RoleType role, UUID requesterInstitutionId) {
        UUID incidentId = incidentIds.getLast();
        return equipmentIncidentQueryService.getIncidentReferrals(role, requesterInstitutionId, incidentId,
                        new IncidentReferralSearchCondition(null, 0, 10)).contents().stream()
                .filter(item -> item.referralId().equals(referralId))
                .findFirst().orElseThrow();
    }

    // 가장 최근 장애를 등록한 장비 소유 기관 (EXAMINER로 조회할 때 쓴다)
    private UUID incidentInstitutionId() {
        return equipmentIncidentRepository.findById(incidentIds.getLast()).orElseThrow().getInstitutionId();
    }

    // ---------- 4·5·6. 재예약 409, 새 예약·Outbox 미생성 ----------

    @ParameterizedTest
    @EnumSource(value = ExaminationStatus.class, names = {"IN_PROGRESS", "COMPLETED"})
    @DisplayName("재조정 필요 의뢰라도 검사가 시작·완료됐으면 재예약은 409이고 새 예약·Outbox·이력이 생기지 않는다")
    void rescheduleRejectedWhenExaminationStarted(ExaminationStatus examinationStatus) {
        // 실제 사고 데이터와 같은 모양: 차단된 뒤 검사 상태가 진행·완료인 의뢰 (이번 수정 전 처리 결과)
        blockByIncident();
        moveExaminationTo(examinationStatus);
        long outboxCount = outboxEventRepository.count();
        int historyCount = histories().size();

        assertThatThrownBy(this::reschedule)
                .isInstanceOf(BaseException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.EXAMINATION_RESERVATION_NOT_CHANGEABLE);

        assertThat(reservations()).hasSize(1);
        assertThat(outboxEventRepository.count()).isEqualTo(outboxCount);
        assertThat(histories()).hasSize(historyCount);
        assertThat(referralStatus()).isEqualTo(ReferralStatus.NEEDS_RESCHEDULE);
        assertThat(referralRepository.findById(referralId).orElseThrow().getCurrentReservationId())
                .isEqualTo(firstReservationId);
    }

    // ---------- 7. 검사 시작(ExaminationService.changeStatus) vs 장애 처리(IncidentProcessingService.process) ----------
    // 두 작업은 각자 독립된 트랜잭션으로 다른 스레드에서 실행한다.
    // 실행 순서는 CountDownLatch와 pg_locks(다른 세션이 잠금을 기다리는지)로만 정하고, 시간 지연에 기대지 않는다.

    @Test
    @DisplayName("[결정적] A. 검사 시작이 먼저 커밋되면 장애 처리는 검사 잠금을 기다린 뒤 SKIPPED_IN_PROGRESS로 끝난다")
    void examinationStartWinsThenIncidentSkips() throws Exception {
        UUID taskId = pendingIncidentTask();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch commitStart = new CountDownLatch(1);
        // 검사 시작: startIfScheduled UPDATE가 끝난 뒤(검사 행 잠금 보유) 커밋 직전에서 멈춘다
        doAnswer(invocation -> {
            Object updated = realRepository().answer(invocation);
            started.countDown();
            await(commitStart);
            return updated;
        }).when(examinationRepository).startIfScheduled(any(), any(), any());

        Future<Attempt> start = executor.submit(() -> attempt(this::startExamination));
        await(started);
        Future<Attempt> incident = executor.submit(() -> attempt(() -> incidentProcessingService.process(taskId)));
        waitUntilSomeSessionWaitsForLock();   // 장애 처리가 검사 행 잠금(findByReferralIdForUpdate)을 기다린다
        commitStart.countDown();

        Attempt startResult = get(start);
        Attempt incidentResult = get(incident);
        String state = state(taskId, startResult, incidentResult);
        assertThat(startResult.error()).as(state).isNull();
        assertThat(incidentResult.value()).as(state).isEqualTo(Outcome.SUCCEEDED);
        assertThat(resultCode(taskId)).as(state).isEqualTo(IncidentProcessingService.SKIPPED_IN_PROGRESS);
        assertThat(examinationStatus()).as(state).isEqualTo(ExaminationStatus.IN_PROGRESS);
        assertThat(reservationStatus(firstReservationId)).as(state).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(referralStatus()).as(state).isEqualTo(ReferralStatus.CONFIRMED);
        assertThat(notificationCount()).as(state).isZero();
        assertThat(blockedHistoryCount()).as(state).isZero();
    }

    @Test
    @DisplayName("[결정적] B. 장애 처리가 먼저 커밋되면 이후 검사 시작은 EXAMINATION_START_NOT_ALLOWED로 거부되고 검사는 SCHEDULED")
    void incidentWinsThenExaminationStartRejected() throws Exception {
        UUID taskId = pendingIncidentTask();

        Attempt incidentResult = get(executor.submit(() -> attempt(() -> incidentProcessingService.process(taskId))));
        Attempt startResult = get(executor.submit(() -> attempt(this::startExamination)));

        String state = state(taskId, startResult, incidentResult);
        assertThat(incidentResult.value()).as(state).isEqualTo(Outcome.SUCCEEDED);
        assertThat(resultCode(taskId)).as(state).isEqualTo(IncidentProcessingService.RESCHEDULE_REQUIRED);
        assertThat(startResult.error()).as(state).isInstanceOf(BaseException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.EXAMINATION_START_NOT_ALLOWED);
        assertThat(examinationStatus()).as(state).isEqualTo(ExaminationStatus.SCHEDULED);
        assertThat(reservationStatus(firstReservationId)).as(state).isEqualTo(ReservationStatus.BLOCKED);
        assertThat(referralStatus()).as(state).isEqualTo(ReferralStatus.NEEDS_RESCHEDULE);
        assertThat(blockedHistoryCount()).as(state).isEqualTo(1);
    }

    @Test
    @DisplayName("[결정적 경쟁 C1] 검사 시작이 예약 검증을 마치고 UPDATE하기 전이라도 장애 처리는 끼어들지 못하고 잠금을 기다린다 (BLOCKED 예약에 IN_PROGRESS 검사 없음)")
    void incidentCommitsBetweenStartValidationAndUpdate() throws Exception {
        UUID taskId = pendingIncidentTask();
        CountDownLatch validated = new CountDownLatch(1);
        CountDownLatch proceed = new CountDownLatch(1);
        // 검사 시작: 의뢰·예약·검사를 잠그고 validateCanStart()(의뢰 CONFIRMED·예약 CONFIRMED 확인)를 통과한 뒤,
        // startIfScheduled UPDATE 직전에서 멈춘다 (수정 전에는 이 시점에 잠금이 없어 장애 처리가 끝까지 커밋할 수 있었다)
        doAnswer(invocation -> {
            validated.countDown();
            await(proceed);
            return realRepository().answer(invocation);
        }).when(examinationRepository).startIfScheduled(any(), any(), any());

        Future<Attempt> start = executor.submit(() -> attempt(this::startExamination));
        await(validated);
        // 그 사이 장애 처리를 시작한다. 검사 시작이 잡은 의뢰 잠금 때문에 차단하지 못하고 기다려야 한다
        Future<Attempt> incident = executor.submit(() -> attempt(() -> incidentProcessingService.process(taskId)));
        waitUntilSomeSessionWaitsForLock();
        assertThat(incident.isDone()).as("장애 처리는 검사 시작의 잠금을 기다려야 한다").isFalse();
        proceed.countDown();
        Attempt startResult = get(start);
        Attempt incidentResult = get(incident);

        assertNoStartedExaminationOnBlockedReservation(taskId, startResult, incidentResult);
        // 검증을 먼저 끝낸 검사 시작이 이기고, 장애 처리는 검사 진행 중으로 보고 제외한다
        String state = state(taskId, startResult, incidentResult);
        assertThat(examinationStatus()).as(state).isEqualTo(ExaminationStatus.IN_PROGRESS);
        assertThat(resultCode(taskId)).as(state).isEqualTo(IncidentProcessingService.SKIPPED_IN_PROGRESS);
        assertThat(notificationCount()).as(state).isZero();
        assertThat(blockedHistoryCount()).as(state).isZero();
    }

    @Test
    @DisplayName("[결정적 경쟁 C2] 장애 처리가 검사 행을 잠근 동안 검증을 통과한 검사 시작이 잠금을 기다렸다가 UPDATE해도, BLOCKED 예약의 검사가 IN_PROGRESS가 되면 안 된다")
    void examinationStartWaitsOnIncidentLockThenUpdates() throws Exception {
        UUID taskId = pendingIncidentTask();
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch commitIncident = new CountDownLatch(1);
        // 장애 처리: findByReferralIdForUpdate로 검사 행을 잠근(SCHEDULED 확인) 직후 멈춘다
        doAnswer(invocation -> {
            Object examination = realRepository().answer(invocation);
            locked.countDown();
            await(commitIncident);
            return examination;
        }).when(examinationRepository).findByReferralIdForUpdate(any());

        Future<Attempt> incident = executor.submit(() -> attempt(() -> incidentProcessingService.process(taskId)));
        await(locked);
        // 검사 시작: 차단 전이 커밋되지 않아 예약 CONFIRMED로 검증을 통과하고, startIfScheduled가 검사 행 잠금을 기다린다
        Future<Attempt> start = executor.submit(() -> attempt(this::startExamination));
        waitUntilSomeSessionWaitsForLock();
        commitIncident.countDown();

        Attempt incidentResult = get(incident);
        Attempt startResult = get(start);

        assertNoStartedExaminationOnBlockedReservation(taskId, startResult, incidentResult);
        // 검사 행을 먼저 잠근 장애 처리가 이기고, 검사 시작은 차단된 예약을 보고 거부된다
        String state = state(taskId, startResult, incidentResult);
        assertThat(resultCode(taskId)).as(state).isEqualTo(IncidentProcessingService.RESCHEDULE_REQUIRED);
        assertThat(startResult.error()).as(state).isInstanceOf(BaseException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.EXAMINATION_START_NOT_ALLOWED);
        assertThat(examinationStatus()).as(state).isEqualTo(ExaminationStatus.SCHEDULED);
    }

    @RepeatedTest(20)
    @DisplayName("[비결정적] D. 검사 시작과 장애 처리를 동시에 시작해도 BLOCKED 예약에 IN_PROGRESS 검사가 남지 않아야 한다")
    void simultaneousStart() throws Exception {
        UUID taskId = pendingIncidentTask();
        CountDownLatch go = new CountDownLatch(1);

        Future<Attempt> start = executor.submit(() -> {
            await(go);
            return attempt(this::startExamination);
        });
        Future<Attempt> incident = executor.submit(() -> {
            await(go);
            return attempt(() -> incidentProcessingService.process(taskId));
        });
        go.countDown();

        assertNoStartedExaminationOnBlockedReservation(taskId, get(start), get(incident));
    }

    // 불변 조건: 예약이 BLOCKED(의뢰 NEEDS_RESCHEDULE)라면 검사는 시작되지 않아야 하고, 검사가 시작됐다면 예약은 차단되지 않아야 한다
    private void assertNoStartedExaminationOnBlockedReservation(UUID taskId, Attempt startResult, Attempt incidentResult) {
        String state = state(taskId, startResult, incidentResult);
        boolean blocked = reservationStatus(firstReservationId) == ReservationStatus.BLOCKED;
        boolean started = examinationStatus() != ExaminationStatus.SCHEDULED;
        assertThat(blocked && started)
                .as("BLOCKED 예약에 시작된 검사가 남음. %s", state)
                .isFalse();
        // 어느 한쪽만 이겨야 한다: 검사 시작 성공 ↔ SKIPPED_IN_PROGRESS, 차단 성공 ↔ 검사 시작 거부
        if (started) {
            assertThat(resultCode(taskId)).as(state).isEqualTo(IncidentProcessingService.SKIPPED_IN_PROGRESS);
        } else {
            assertThat(startResult.error()).as(state).isInstanceOf(BaseException.class);
        }
        // 차단 이력·재조정 알림은 차단된 경우에만, 정확히 1건
        long expected = blocked ? 1 : 0;
        assertThat(blockedHistoryCount()).as(state).isEqualTo(expected);
        assertThat(notificationCount()).as(state).isEqualTo(expected);
    }

    // ---------- 도우미 ----------

    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    @AfterEach
    void stopExecutor() {
        executor.shutdownNow();
    }

    // 실행 결과 또는 예외 (다른 스레드의 결과를 비교·출력하기 위해 담는다)
    private record Attempt(Object value, Throwable error) {
        @Override
        public String toString() {
            return error == null ? String.valueOf(value)
                    : error.getClass().getSimpleName() + (error instanceof BaseException b ? "(" + b.getErrorCode() + ")" : "");
        }
    }

    private static Attempt attempt(Supplier<?> action) {
        try {
            return new Attempt(action.get(), null);
        } catch (RuntimeException e) {
            return new Attempt(null, e);
        }
    }

    // 검사기관 담당자의 검사 시작 요청 (ExaminationService.changeStatus, 자체 트랜잭션)
    private Object startExamination() {
        Examination examination = examinationRepository.findByReferralId(referralId).orElseThrow();
        return examinationService.changeStatus(examination.getId(), examination.getExamInstitutionId(),
                RoleType.EXAMINER, ExaminationStatus.IN_PROGRESS);
    }

    // 스파이의 기본 동작(실제 Repository 호출). 멈춘 뒤 실제 쿼리를 이어서 실행할 때 쓴다
    @SuppressWarnings("unchecked")
    private Answer<Object> realRepository() {
        return (Answer<Object>) Mockito.mockingDetails(examinationRepository).getMockCreationSettings().getDefaultAnswer();
    }

    // 다른 세션이 행 잠금을 얻지 못하고 기다리는 상태(pg_locks.granted = false)가 될 때까지 조건을 확인한다
    private void waitUntilSomeSessionWaitsForLock() {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbcTemplate.queryForObject("select count(*) from pg_locks where not granted", Integer.class);
            if (waiting != null && waiting > 0) {
                return;
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
        }
        throw new AssertionError("다른 세션이 잠금 대기에 들어가지 않았다");
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("신호를 받지 못했다");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static Attempt get(Future<Attempt> future) throws Exception {
        return future.get(15, TimeUnit.SECONDS);
    }

    // 실패 시 출력할 DB 최종 상태와 각 스레드의 결과
    private String state(UUID taskId, Attempt startResult, Attempt incidentResult) {
        IncidentReferral task = incidentReferralRepository.findById(taskId).orElseThrow();
        return "[DB 최종 상태] 예약=" + reservationStatus(firstReservationId) + ", 의뢰=" + referralStatus()
                + ", 검사=" + examinationStatus() + ", 장애작업=" + task.getProcessingStatus() + "/" + task.getResultCode()
                + ", 재조정알림=" + notificationCount() + ", 차단이력=" + blockedHistoryCount()
                + " [실행 결과] 검사시작=" + startResult + ", 장애처리=" + incidentResult;
    }

    private ExaminationStatus examinationStatus() {
        return examinationRepository.findByReferralId(referralId).orElseThrow().getStatus();
    }

    private long blockedHistoryCount() {
        return histories().stream().filter(h -> h.getReason() == Reason.RESERVATION_BLOCKED).count();
    }

    private String resultCode(UUID taskId) {
        return incidentReferralRepository.findById(taskId).orElseThrow().getResultCode();
    }

    // 예약(14~15시)을 덮는 장애를 등록하고 후속 작업(PENDING) 1건을 만든다
    private UUID pendingIncidentTask() {
        UUID incidentId = equipmentIncidentService.register(UUID.randomUUID(), equipmentId, UUID.randomUUID(),
                "가상 장애 사유", LocalDateTime.now(), startsAt.minusHours(1), startsAt.plusHours(2));
        incidentIds.add(incidentId);
        assertThat(equipmentIncidentService.createPendingReferrals(incidentId)).isEqualTo(1);
        return incidentReferralRepository.findAll().stream()
                .filter(t -> t.getIncident().getId().equals(incidentId))
                .findFirst().orElseThrow().getId();
    }

    // 예약(14~15시)을 덮는 장애를 등록하고 후속 작업 1건을 처리한다
    private IncidentReferral blockByIncident() {
        UUID taskId = pendingIncidentTask();
        assertThat(incidentProcessingService.process(taskId)).isEqualTo(Outcome.SUCCEEDED);
        return incidentReferralRepository.findById(taskId).orElseThrow();
    }

    // 검사기관의 검사 시작·완료와 같은 조건부 UPDATE로 검사 상태를 바꾼다
    private void moveExaminationTo(ExaminationStatus target) {
        Examination examination = examinationRepository.findByReferralId(referralId).orElseThrow();
        assertThat(examinationRepository.startIfScheduled(examination.getId(), examination.getReservationId(),
                LocalDateTime.now())).isEqualTo(1);
        if (target == ExaminationStatus.COMPLETED) {
            assertThat(examinationRepository.completeIfInProgress(examination.getId(), LocalDateTime.now())).isEqualTo(1);
        }
    }

    private ReservationResponse reschedule() {
        CreateReservationRequest request = CreateReservationRequest.builder()
                .slotId(UUID.randomUUID())
                .examInstitutionId(UUID.randomUUID())
                .equipmentId(UUID.randomUUID())
                .examDate(startsAt.plusDays(1))
                .startTime(startsAt.plusDays(1))
                .endTime(startsAt.plusDays(1).plusHours(1))
                .build();
        return referralService.createRescheduledReservation(userId, RoleType.REQUESTER, referralId, request);
    }

    private void slotResult(SlotResultType type, UUID reservationId, UUID eventId) {
        eventIds.add(eventId);
        slotResultService.handle(eventId, type, reservationId);
    }

    private List<Reservation> reservations() {
        return reservationRepository.findAll().stream()
                .filter(r -> r.getReferral().getId().equals(referralId))
                .toList();
    }

    private ReservationStatus reservationStatus(UUID reservationId) {
        return reservationRepository.findById(reservationId).orElseThrow().getReservationStatus();
    }

    private ReferralStatus referralStatus() {
        return referralRepository.findById(referralId).orElseThrow().getReferralStatus();
    }

    private String resultCode(IncidentReferral task) {
        return incidentReferralRepository.findById(task.getId()).orElseThrow().getResultCode();
    }

    private long notificationCount() {
        return notificationRepository.findAll().stream().filter(n -> referralId.equals(n.getReferralId())).count();
    }

    private List<ReferralHistory> histories() {
        return referralHistoryRepository.findAllByReferralIdOrderByOccurredAtAsc(referralId);
    }

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }
}
