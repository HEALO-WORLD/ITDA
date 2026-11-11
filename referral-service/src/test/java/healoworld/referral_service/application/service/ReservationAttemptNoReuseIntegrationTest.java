package healoworld.referral_service.application.service;

import healoworld.referral_service.application.event.result.SlotResultType;
import healoworld.referral_service.application.service.IncidentProcessingService.Outcome;
import healoworld.referral_service.domain.entity.Referral;
import healoworld.referral_service.domain.entity.Reservation;
import healoworld.referral_service.domain.enums.ReferralStatus;
import healoworld.referral_service.domain.enums.ReservationStatus;
import healoworld.referral_service.domain.enums.Role;
import healoworld.referral_service.global.config.ClockConfig;
import healoworld.referral_service.global.config.QuerydslConfig;
import healoworld.referral_service.global.exception.BaseException;
import healoworld.referral_service.global.exception.ErrorCode;
import healoworld.referral_service.global.outbox.SlotCommandOutbox;
import healoworld.referral_service.global.dto.ApiResponse;
import healoworld.referral_service.infrastructure.client.institution.EquipmentOwnerResponse;
import healoworld.referral_service.infrastructure.client.institution.InstitutionClient;
import healoworld.referral_service.infrastructure.client.institution.PartnershipCheckResponse;
import healoworld.referral_service.infrastructure.repository.EquipmentIncidentLockRepository;
import healoworld.referral_service.infrastructure.repository.IncidentReferralRepository;
import healoworld.referral_service.infrastructure.repository.ReferralRepository;
import healoworld.referral_service.infrastructure.repository.ReservationRepository;
import healoworld.referral_service.presentation.dto.request.CreateReservationRequest;
import healoworld.referral_service.support.PostgresContainerSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

/**
 * 결함 재현 테스트: 소프트 삭제된 예약 때문에 재예약 시도 번호(attemptNo)가 다시 쓰이는지 확인한다.
 * <p>
 * - Reservation의 @SQLRestriction("deleted_at IS NULL") 때문에 findTopByReferralIdOrderByAttemptNoDesc()가
 *   삭제된 예약을 보지 못하면, 재예약의 attemptNo가 이전 번호를 다시 쓸 수 있다
 * - attemptNo가 1이 되면 SlotResultService.failOrRequestReschedule()이 첫 예약으로 판단해 의뢰를 FAILED로 바꿀 수 있다
 * - 예약 확정 → 장비 장애 차단 → 예약 삭제 → 재예약 → HOLD 실패까지 실제 서비스를 호출한다 (Kafka·Slot Service는 거치지 않음)
 * - BLOCKED 예약은 현재 삭제 정책(관리자만 FAILED·CANCELED 삭제)에서 API로 지울 수 없으므로,
 *   정책 변경 전에 이미 삭제된 기존 데이터를 재현하기 위해 Repository의 조건부 soft delete를 직접 호출한다
 * - 예약 삭제 정책(역할·상태)도 이 클래스에서 실제 DB로 검증한다 (같은 Spring 컨텍스트를 재사용하기 위해 이 클래스 안에 둔다)
 * - 기대값은 올바른 동작 기준이다. 현재 구현에서 실패하면 결함이 재현된 것이다
 * - 각 작업이 실제로 커밋하므로 테스트 트랜잭션을 끄고, 끝날 때 이 테스트가 만든 행만 지운다
 *   (소프트 삭제된 예약은 JPA 조회에 보이지 않으므로 정리는 JdbcTemplate으로 한다)
 */
@DataJpaTest
@Import({
        ReferralService.class,
        SlotResultService.class,
        InstitutionNotificationService.class,
        EquipmentIncidentService.class,
        EquipmentIncidentLockRepository.class,
        IncidentProcessingService.class,
        NotificationService.class,
        ExaminationService.class,
        ReservationService.class,
        ReferralHistoryService.class,
        SlotCommandOutbox.class,
        QuerydslConfig.class, ClockConfig.class,
        ReservationAttemptNoReuseIntegrationTest.JsonConfig.class
})
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ReservationAttemptNoReuseIntegrationTest extends PostgresContainerSupport {

    @TestConfiguration(proxyBeanMethods = false)
    static class JsonConfig {
        @Bean
        JsonMapper jsonMapper() {
            return JsonMapper.builder().build();
        }
    }

    @Autowired private ReferralService referralService;
    @Autowired private ReservationService reservationService;
    @Autowired private SlotResultService slotResultService;
    @Autowired private EquipmentIncidentService equipmentIncidentService;
    @Autowired private IncidentProcessingService incidentProcessingService;
    @Autowired private ReferralRepository referralRepository;
    @Autowired private ReservationRepository reservationRepository;
    @Autowired private IncidentReferralRepository incidentReferralRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PlatformTransactionManager transactionManager;

    // 재예약 시 협력 관계 확인 (institution-service)
    @MockitoBean private InstitutionClient institutionClient;

    private final UUID userId = UUID.randomUUID();
    private final UUID institutionId = UUID.randomUUID();
    private final UUID equipmentId = UUID.randomUUID();          // 첫 예약의 장비 (검사기관 A 소유)
    private final UUID examInstitutionA = UUID.randomUUID();     // 첫 예약의 검사기관
    private final UUID examInstitutionB = UUID.randomUUID();     // 재예약할 다른 검사기관
    private final UUID equipmentB = UUID.randomUUID();
    private final LocalDateTime startsAt =
            LocalDateTime.now().plusDays(3).withHour(14).withMinute(0).withSecond(0).withNano(0);

    private UUID referralId;
    private UUID firstReservationId;
    private final List<UUID> incidentIds = new ArrayList<>();
    private final List<UUID> eventIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        given(institutionClient.check(any(), any())).willReturn(new PartnershipCheckResponse(true));
        // 첫 예약(attemptNo=1): HOLD까지 끝난 상태 (의뢰 PENDING, 예약 HELD)
        tx().executeWithoutResult(status -> {
            Referral referral = referralRepository.save(Referral.create(institutionId, userId, UUID.randomUUID(),
                    "가상환자", UUID.randomUUID()));
            Reservation reservation = Reservation.create(referral, 1, UUID.randomUUID(), examInstitutionA,
                    equipmentId, startsAt, startsAt, startsAt.plusHours(1));
            reservation.hold();
            reservationRepository.save(reservation);
            referral.assignReservation(reservation.getId());
            referralId = referral.getId();
            firstReservationId = reservation.getId();
        });
        // 확정: 예약 CONFIRMED, 의뢰 CONFIRMED, 검사 SCHEDULED
        slotResult(SlotResultType.SLOT_BOOKED, firstReservationId);
    }

    @AfterEach
    void cleanUp() {
        // 소프트 삭제된 예약까지 포함해 이 의뢰의 행만 FK 순서대로 지운다
        List<UUID> reservationIds = jdbcTemplate.queryForList(
                "select id from p_reservations where referral_id = ?", UUID.class, referralId);
        jdbcTemplate.update("delete from p_notifications where referral_id = ?", referralId);
        jdbcTemplate.update("delete from p_referral_histories where referral_id = ?", referralId);
        reservationIds.forEach(id -> jdbcTemplate.update("delete from p_outbox_events where aggregate_id = ?", id));
        eventIds.forEach(id -> jdbcTemplate.update("delete from p_consumed_events where id = ? and consumer = ?",
                id, SlotResultService.CONSUMER));
        incidentIds.forEach(id -> {
            jdbcTemplate.update("delete from p_incident_referrals where incident_id = ?", id);
            jdbcTemplate.update("delete from p_equipment_incidents where id = ?", id);
        });
        jdbcTemplate.update("delete from p_examinations where referral_id = ?", referralId);
        jdbcTemplate.update("delete from p_reservations where referral_id = ?", referralId);
        jdbcTemplate.update("delete from p_referrals where id = ?", referralId);
    }

    // ---------- T1. 정상 재예약: 시도 번호 증가 ----------

    @Test
    @DisplayName("T1. 장애로 차단된 뒤 재예약하면 새 예약의 attemptNo는 2다")
    void rescheduleIncrementsAttemptNo() {
        blockByIncident();

        UUID newReservationId = reschedule();

        assertThat(attemptNo(newReservationId)).as(reservationRows()).isEqualTo(2);
    }

    // ---------- T2. BLOCKED 예약 삭제 후 재예약 ----------

    @Test
    @DisplayName("T2. 차단된 첫 예약을 삭제한 뒤 재예약해도 새 예약의 attemptNo는 2여야 한다")
    void rescheduleAfterDeletingBlockedReservation() {
        blockByIncident();
        deleteFirstReservation();

        UUID newReservationId = reschedule();

        assertThat(attemptNo(newReservationId)).as(reservationRows()).isEqualTo(2);
    }

    // ---------- T3. 삭제 후 재예약 HOLD 실패 ----------

    @Test
    @DisplayName("T3. 차단된 첫 예약을 삭제하고 재예약한 뒤 HOLD가 실패해도 의뢰는 NEEDS_RESCHEDULE여야 한다")
    void holdFailureAfterDeletingBlockedReservationKeepsNeedsReschedule() {
        blockByIncident();
        deleteFirstReservation();
        UUID newReservationId = reschedule();
        assertThat(referralStatus()).isEqualTo(ReferralStatus.PENDING);

        slotResult(SlotResultType.SLOT_HOLD_FAILED, newReservationId);

        assertThat(reservationStatus(newReservationId)).isEqualTo(ReservationStatus.FAILED);
        assertThat(referralStatus()).as(reservationRows()).isEqualTo(ReferralStatus.NEEDS_RESCHEDULE);
    }

    // ---------- 대조군: 삭제하지 않으면 재예약 HOLD 실패 후 NEEDS_RESCHEDULE ----------

    @Test
    @DisplayName("대조군. 예약을 삭제하지 않고 재예약한 뒤 HOLD가 실패하면 의뢰는 NEEDS_RESCHEDULE로 돌아간다")
    void holdFailureWithoutDeletionKeepsNeedsReschedule() {
        blockByIncident();
        UUID newReservationId = reschedule();

        slotResult(SlotResultType.SLOT_HOLD_FAILED, newReservationId);

        assertThat(reservationStatus(newReservationId)).isEqualTo(ReservationStatus.FAILED);
        assertThat(referralStatus()).as(reservationRows()).isEqualTo(ReferralStatus.NEEDS_RESCHEDULE);
    }

    // ---------- 동시성: 같은 의뢰에 재예약이 동시에 들어와도 시도 번호가 중복되지 않음 ----------

    // 재예약은 의뢰 행 잠금(findByIdForUpdate) → 상태 확인 → MAX(attempt_no) 조회 → 예약 저장 → 의뢰 PENDING 전환을 한 트랜잭션에서 한다
    // 두 요청이 동시에 와도 뒤 요청은 잠금을 기다렸다가 PENDING이 된 의뢰를 보고 거절되므로 같은 번호의 예약이 두 개 생기지 않는다
    @RepeatedTest(5)
    @DisplayName("동시성. 차단 예약을 삭제한 의뢰에 재예약 2건이 동시에 들어오면 1건만 attemptNo 2로 생성되고 1건은 거절된다")
    void concurrentReschedulesDoNotDuplicateAttemptNo() throws Exception {
        blockByIncident();
        deleteFirstReservation();

        // Mock stubbing(given)은 스레드에 안전하지 않으므로 요청 생성과 장비 소속 stubbing은 스레드 시작 전에 끝낸다
        List<CreateReservationRequest> requests = List.of(rescheduleRequest(), rescheduleRequest());

        CountDownLatch ready = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        List<Object> outcomes = new ArrayList<>();
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (CreateReservationRequest request : requests) {
                futures.add(executor.submit(() -> {
                    ready.await();
                    try {
                        return reschedule(request);
                    } catch (BaseException e) {
                        return e.getErrorCode();
                    }
                }));
            }
            ready.countDown();
            for (Future<Object> future : futures) {
                outcomes.add(future.get(30, TimeUnit.SECONDS));
            }
        } finally {
            executor.shutdownNow();
        }

        assertThat(outcomes).as(reservationRows()).filteredOn(UUID.class::isInstance).hasSize(1);
        assertThat(outcomes).as(reservationRows()).containsOnlyOnce(ErrorCode.INVALID_REFERRAL_STATUS);
        UUID newReservationId = (UUID) outcomes.stream().filter(UUID.class::isInstance).findFirst().orElseThrow();
        assertThat(attemptNo(newReservationId)).isEqualTo(2);
        // 삭제된 첫 예약(1)과 새 예약(2)만 있고, 같은 번호의 예약이 더 생기지 않았다
        assertThat(jdbcTemplate.queryForList("select attempt_no from p_reservations where referral_id = ? order by attempt_no",
                Integer.class, referralId)).as(reservationRows()).containsExactly(1, 2);
        assertThat(referralStatus()).isEqualTo(ReferralStatus.PENDING);
    }

    // ---------- attemptNo 계산 경계 ----------
    // 새 Spring 컨텍스트를 만들지 않도록 이 클래스에 둔다 (컨텍스트마다 DB 연결 풀이 생겨 테스트용 PostgreSQL의 연결 수 한도를 넘을 수 있음)
    // 메서드에 @Transactional을 붙여 setUp부터 테스트 트랜잭션 안에서 실행하고 끝나면 롤백한다 (setUp의 의뢰는 이 테스트들에서 쓰지 않음)

    @Test
    @Transactional
    @DisplayName("경계. 예약이 없는 의뢰의 첫 예약은 attemptNo 1이다")
    void firstReservationStartsAtOne() {
        Referral referral = newReferral();

        assertThat(reservationRepository.findMaxAttemptNoIncludingDeleted(referral.getId())).isZero();
        assertThat(create(referral).getAttemptNo()).isEqualTo(1);
    }

    @Test
    @Transactional
    @DisplayName("경계. 이전 예약이 모두 소프트 삭제돼도 마지막 번호 + 1을 쓴다")
    void allReservationsSoftDeleted() {
        Referral referral = newReferral();
        Reservation first = create(referral);
        Reservation second = create(referral);
        Reservation third = create(referral);
        softDelete(first);
        softDelete(second);
        softDelete(third);
        assertThat(reservationRepository.findById(third.getId())).isEmpty();   // JPA 조회에서는 보이지 않음

        assertThat(reservationRepository.findMaxAttemptNoIncludingDeleted(referral.getId())).isEqualTo(3);
        assertThat(create(referral).getAttemptNo()).isEqualTo(4);
    }

    @Test
    @Transactional
    @DisplayName("경계. 마지막 예약만 소프트 삭제돼도 그 번호를 다시 쓰지 않는다")
    void onlyLatestSoftDeleted() {
        Referral referral = newReferral();
        create(referral);
        softDelete(create(referral));

        assertThat(create(referral).getAttemptNo()).isEqualTo(3);
    }

    @Test
    @Transactional
    @DisplayName("경계. 서로 다른 의뢰의 attemptNo는 섞이지 않는다")
    void attemptNoIsPerReferral() {
        Referral referralA = newReferral();
        Referral referralB = newReferral();
        create(referralA);
        softDelete(create(referralA));
        create(referralA);                  // A: 1, 2(삭제), 3
        create(referralB);                  // B: 1

        assertThat(reservationRepository.findMaxAttemptNoIncludingDeleted(referralA.getId())).isEqualTo(3);
        assertThat(reservationRepository.findMaxAttemptNoIncludingDeleted(referralB.getId())).isEqualTo(1);
        assertThat(create(referralB).getAttemptNo()).isEqualTo(2);
        assertThat(create(referralA).getAttemptNo()).isEqualTo(4);
        assertThat(reservationRepository.findMaxAttemptNoIncludingDeleted(UUID.randomUUID())).isZero();
    }

    private Referral newReferral() {
        return referralRepository.saveAndFlush(Referral.create(UUID.randomUUID(), userId, UUID.randomUUID(),
                "가상환자", UUID.randomUUID()));
    }

    // 실제 예약 생성 경로(attemptNo 계산 + HoldRequested Outbox 기록)로 만든다
    private Reservation create(Referral referral) {
        CreateReservationRequest request = CreateReservationRequest.builder()
                .slotId(UUID.randomUUID())
                .examInstitutionId(UUID.randomUUID())
                .equipmentId(UUID.randomUUID())
                .examDate(startsAt)
                .startTime(startsAt)
                .endTime(startsAt.plusHours(1))
                .build();
        Reservation reservation = reservationService.createReservation(UUID.randomUUID(), referral, request);
        reservationRepository.flush();
        return reservation;
    }

    // 과거 삭제 API가 쓰던 조건부 소프트 삭제(기존 데이터 재현용). 테스트 예약은 REQUESTED 상태다
    private void softDelete(Reservation reservation) {
        assertThat(reservationRepository.softDeleteIf(reservation.getId(), userId, LocalDateTime.now(),
                List.of(ReservationStatus.REQUESTED))).isEqualTo(1);
    }

    // ---------- 예약 삭제 API 제거 후: 과거 삭제 데이터와 취소 회귀 (실제 DB) ----------
    // 예약 삭제 API는 제공하지 않는다. 과거에 삭제된 예약은 Repository의 조건부 soft delete로 재현한다
    // @Nested로 묶으면 별도 Spring 컨텍스트(DB 연결 풀)가 생겨 테스트 DB 연결 한도를 넘으므로 이 클래스에 바로 둔다

    @Test
    @DisplayName("과거에 재예약 실패(FAILED) 예약이 삭제된 의뢰도 NEEDS_RESCHEDULE이고 다음 재예약은 번호를 다시 쓰지 않는다")
    void legacyDeletedFailedRetryThenReschedules() {
        blockByIncident();
        UUID failedId = reschedule();                                   // attemptNo 2
        slotResult(SlotResultType.SLOT_HOLD_FAILED, failedId);
        assertThat(reservationStatus(failedId)).isEqualTo(ReservationStatus.FAILED);
        assertThat(referralStatus()).isEqualTo(ReferralStatus.NEEDS_RESCHEDULE);

        Integer deleted = tx().execute(status -> reservationRepository.softDeleteIf(failedId, userId,
                LocalDateTime.now(), List.of(ReservationStatus.FAILED)));
        assertThat(deleted).isEqualTo(1);
        assertThat(referralStatus()).isEqualTo(ReferralStatus.NEEDS_RESCHEDULE);

        UUID nextId = reschedule();
        assertThat(attemptNo(nextId)).as(reservationRows()).isEqualTo(3);
    }

    @Test
    @DisplayName("예약 취소는 그대로 동작하고 예약 행을 삭제하지 않는다 (예약·의뢰 CANCELED, deleted_at 없음)")
    void cancelKeepsReservationRow() {
        reservationService.cancelReservation(userId, institutionId, Role.REQUESTER, firstReservationId);

        assertThat(reservationStatus(firstReservationId)).isEqualTo(ReservationStatus.CANCELED);
        assertThat(referralStatus()).isEqualTo(ReferralStatus.CANCELED);
        assertThat(jdbcTemplate.queryForObject("select deleted_at is null from p_reservations where id = ?",
                Boolean.class, firstReservationId)).isTrue();
        assertThat(reservationRepository.findById(firstReservationId)).isPresent();
    }

    // ---------- STEP 4: 현재 재조정 필요 의뢰 수 (실제 장애 처리·재예약 흐름) ----------
    // ReferralRepository.countNeedsRescheduleByLatestBlockedInstitution()을 실제 서비스가 만든 상태로 검증한다
    // A = 첫 예약의 검사기관(장비 equipmentId), B = 재예약한 검사기관(장비 equipmentB). 의뢰는 이 테스트의 한 건뿐이다

    @Test
    @DisplayName("T1. A 기관 장비 장애로 예약이 차단되면 A=1")
    void step4BlockedByIncident() {
        blockByIncident();

        assertRescheduleCounts(1, 0);
    }

    @Test
    @DisplayName("T2. A 차단 후 재예약을 요청해 의뢰가 PENDING이면 A=0")
    void step4RescheduleRequested() {
        blockByIncident();
        reschedule();
        assertThat(referralStatus()).isEqualTo(ReferralStatus.PENDING);

        assertRescheduleCounts(0, 0);
    }

    @Test
    @DisplayName("T3. A 차단 후 재예약이 확정되면 A=0")
    void step4RescheduleConfirmed() {
        blockByIncident();
        confirm(rescheduleToB());
        assertThat(referralStatus()).isEqualTo(ReferralStatus.CONFIRMED);

        assertRescheduleCounts(0, 0);
    }

    @Test
    @DisplayName("T4. A 차단 후 B로 재예약했다가 HOLD가 실패하면(현재 예약 FAILED) 원인인 A=1, B=0")
    void step4RescheduleHoldFailed() {
        blockByIncident();
        UUID retryId = rescheduleToB();
        slotResult(SlotResultType.SLOT_HOLD_FAILED, retryId);
        assertThat(referralStatus()).isEqualTo(ReferralStatus.NEEDS_RESCHEDULE);
        assertThat(reservationStatus(retryId)).isEqualTo(ReservationStatus.FAILED);
        assertThat(attemptNo(retryId)).isEqualTo(2);

        assertRescheduleCounts(1, 0);
    }

    @Test
    @DisplayName("T5. A 차단 → B 재예약·확정 → B 장비 장애로 차단되면 A=0, B=1 (attemptNo 1 → 2)")
    void step4BlockedAgainAtOtherInstitution() {
        blockByIncident();
        UUID retryId = rescheduleToB();
        confirm(retryId);

        assertThat(process(pendingTask(equipmentB, examInstitutionB, retryStartsAt().minusHours(1),
                retryStartsAt().plusHours(2)))).isEqualTo(IncidentProcessingService.RESCHEDULE_REQUIRED);
        assertThat(attemptNo(retryId)).isEqualTo(2);

        assertRescheduleCounts(0, 1);
    }

    @Test
    @DisplayName("T6·T7. 같은 예약이 장애 2건에 연결돼 두 번째가 ALREADY_BLOCKED여도 A에 한 번만 센다")
    void step4SameReferralManyIncidents() {
        // 14~15시 예약과 각각 겹치는 서로 다른 중단 구간 2건 (장비 중단끼리는 겹치지 않음)
        UUID first = pendingTask(equipmentId, examInstitutionA, startsAt.minusHours(1), startsAt.plusMinutes(30));
        UUID second = pendingTask(equipmentId, examInstitutionA, startsAt.plusMinutes(30), startsAt.plusHours(2));

        assertThat(process(first)).isEqualTo(IncidentProcessingService.RESCHEDULE_REQUIRED);
        assertThat(process(second)).isEqualTo(IncidentProcessingService.ALREADY_BLOCKED);

        assertRescheduleCounts(1, 0);
    }

    @Test
    @DisplayName("T7. 늦게 처리된 A 장애 작업이 SKIPPED_NOT_CURRENT여도 집계가 바뀌지 않고, 이후 B 차단이 원인이 된다")
    void step4SkippedTaskKeepsLatestCause() {
        UUID first = pendingTask(equipmentId, examInstitutionA, startsAt.minusHours(1), startsAt.plusMinutes(30));
        UUID late = pendingTask(equipmentId, examInstitutionA, startsAt.plusMinutes(30), startsAt.plusHours(2));
        assertThat(process(first)).isEqualTo(IncidentProcessingService.RESCHEDULE_REQUIRED);
        UUID retryId = rescheduleToB();
        confirm(retryId);

        assertThat(process(late)).isEqualTo(IncidentProcessingService.SKIPPED_NOT_CURRENT);
        assertThat(referralStatus()).isEqualTo(ReferralStatus.CONFIRMED);
        assertRescheduleCounts(0, 0);

        assertThat(process(pendingTask(equipmentB, examInstitutionB, retryStartsAt().minusHours(1),
                retryStartsAt().plusHours(2)))).isEqualTo(IncidentProcessingService.RESCHEDULE_REQUIRED);
        assertRescheduleCounts(0, 1);
    }

    @Test
    @DisplayName("T8. 차단된 예약이 과거에 소프트 삭제됐어도 A=1이고, 이후 재예약 HOLD가 실패해도 A=1")
    void step4SoftDeletedBlockedReservation() {
        blockByIncident();
        deleteFirstReservation();
        assertRescheduleCounts(1, 0);

        UUID retryId = rescheduleToB();
        slotResult(SlotResultType.SLOT_HOLD_FAILED, retryId);
        assertThat(referralStatus()).isEqualTo(ReferralStatus.NEEDS_RESCHEDULE);
        assertRescheduleCounts(1, 0);
    }

    @Test
    @DisplayName("T9. SlotsBlocked로 확정 예약이 차단되면 그 예약의 검사기관 A=1 (IncidentReferral 없음)")
    void step4SlotsBlockedConfirmed() {
        slotResult(SlotResultType.SLOTS_BLOCKED, firstReservationId);
        assertThat(reservationStatus(firstReservationId)).isEqualTo(ReservationStatus.BLOCKED);
        assertThat(referralStatus()).isEqualTo(ReferralStatus.NEEDS_RESCHEDULE);

        assertRescheduleCounts(1, 0);
    }

    @Test
    @DisplayName("T9. A 차단 후 B 재예약(REQUESTED)이 SlotsBlocked로 차단되면 A=0, B=1")
    void step4SlotsBlockedRescheduleAttempt() {
        blockByIncident();
        UUID retryId = rescheduleToB();
        slotResult(SlotResultType.SLOTS_BLOCKED, retryId);
        assertThat(reservationStatus(retryId)).isEqualTo(ReservationStatus.BLOCKED);
        assertThat(referralStatus()).isEqualTo(ReferralStatus.NEEDS_RESCHEDULE);

        assertRescheduleCounts(0, 1);
    }

    @Test
    @DisplayName("T10·T12. 확정(CONFIRMED)·취소(CANCELED) 의뢰는 집계하지 않는다")
    void step4ConfirmedAndCanceledExcluded() {
        assertRescheduleCounts(0, 0);   // CONFIRMED

        reservationService.cancelReservation(userId, institutionId, Role.REQUESTER, firstReservationId);
        assertThat(referralStatus()).isEqualTo(ReferralStatus.CANCELED);
        assertRescheduleCounts(0, 0);
    }

    private void assertRescheduleCounts(long expectedA, long expectedB) {
        assertThat(referralRepository.countNeedsRescheduleByLatestBlockedInstitution(examInstitutionA))
                .as("A, " + reservationRows()).isEqualTo(expectedA);
        assertThat(referralRepository.countNeedsRescheduleByLatestBlockedInstitution(examInstitutionB))
                .as("B, " + reservationRows()).isEqualTo(expectedB);
    }

    // ---------- 준비 단계 ----------

    // 재예약 검사 시각 (첫 예약 다음 날 14~15시)
    private LocalDateTime retryStartsAt() {
        return startsAt.plusDays(1);
    }

    // 검사기관 B의 장비로 재예약한다
    private UUID rescheduleToB() {
        CreateReservationRequest request = CreateReservationRequest.builder()
                .slotId(UUID.randomUUID())
                .examInstitutionId(examInstitutionB)
                .equipmentId(equipmentB)
                .examDate(retryStartsAt())
                .startTime(retryStartsAt())
                .endTime(retryStartsAt().plusHours(1))
                .build();
        givenEquipmentOwnedBy(request);
        return referralService.createRescheduledReservation(userId, Role.REQUESTER, referralId, request).getId();
    }

    // Slot의 HOLD 성공 → 확정 결과를 차례로 받는다: 예약 CONFIRMED, 의뢰 CONFIRMED
    private void confirm(UUID reservationId) {
        slotResult(SlotResultType.SLOT_HELD, reservationId);
        slotResult(SlotResultType.SLOT_BOOKED, reservationId);
        assertThat(reservationStatus(reservationId)).isEqualTo(ReservationStatus.CONFIRMED);
    }

    // 장비 중단 구간으로 장애를 등록하고 영향 예약의 후속 작업(PENDING)을 만든다. 이 의뢰의 작업 ID를 돌려준다
    private UUID pendingTask(UUID equipment, UUID ownerInstitutionId, LocalDateTime from, LocalDateTime to) {
        UUID incidentId = equipmentIncidentService.register(UUID.randomUUID(), equipment, ownerInstitutionId,
                "가상 장애 사유", LocalDateTime.now(), from, to);
        incidentIds.add(incidentId);
        equipmentIncidentService.createPendingReferrals(incidentId);
        return incidentReferralRepository.findAll().stream()
                .filter(t -> t.getIncident().getId().equals(incidentId) && t.getReferralId().equals(referralId))
                .findFirst().orElseThrow().getId();
    }

    // 후속 작업을 실제로 처리하고 결과 코드를 돌려준다
    private String process(UUID taskId) {
        assertThat(incidentProcessingService.process(taskId)).isEqualTo(Outcome.SUCCEEDED);
        return incidentReferralRepository.findById(taskId).orElseThrow().getResultCode();
    }

    // 예약(14~15시)을 덮는 장애를 등록하고 후속 작업을 실제로 처리한다: 예약 BLOCKED, 의뢰 NEEDS_RESCHEDULE
    private void blockByIncident() {
        UUID incidentId = equipmentIncidentService.register(UUID.randomUUID(), equipmentId, UUID.randomUUID(),
                "가상 장애 사유", LocalDateTime.now(), startsAt.minusHours(1), startsAt.plusHours(2));
        incidentIds.add(incidentId);
        assertThat(equipmentIncidentService.createPendingReferrals(incidentId)).isEqualTo(1);
        UUID taskId = incidentReferralRepository.findAll().stream()
                .filter(t -> t.getIncident().getId().equals(incidentId))
                .findFirst().orElseThrow().getId();
        assertThat(incidentProcessingService.process(taskId)).isEqualTo(Outcome.SUCCEEDED);

        assertThat(reservationStatus(firstReservationId)).isEqualTo(ReservationStatus.BLOCKED);
        assertThat(referralStatus()).isEqualTo(ReferralStatus.NEEDS_RESCHEDULE);
    }

    // 예약 삭제 API를 제공하던 때(본인이 BLOCKED 예약 삭제 가능) 이미 소프트 삭제된 기존 데이터를 재현한다
    // 지금은 삭제 API가 없으므로 그때 쓰던 Repository의 조건부 soft delete를 직접 호출한다
    private void deleteFirstReservation() {
        Integer deleted = tx().execute(status -> reservationRepository.softDeleteIf(firstReservationId, userId,
                LocalDateTime.now(), List.of(ReservationStatus.BLOCKED)));
        assertThat(deleted).isEqualTo(1);

        assertThat(jdbcTemplate.queryForObject(
                "select deleted_at is not null from p_reservations where id = ?", Boolean.class, firstReservationId))
                .isTrue();
        assertThat(reservationRepository.findById(firstReservationId)).isEmpty();   // JPA 조회에서는 보이지 않음
    }

    // 재예약 요청의 장비를 요청한 검사기관 소속으로 응답하게 한다
    private void givenEquipmentOwnedBy(CreateReservationRequest request) {
        given(institutionClient.getEquipmentOwner(request.getEquipmentId()))
                .willReturn(ApiResponse.ok("장비 소속 기관 조회에 성공했습니다.",
                        new EquipmentOwnerResponse(request.getEquipmentId(), request.getExamInstitutionId())));
    }

    private UUID reschedule() {
        return reschedule(rescheduleRequest());
    }

    // 재예약 요청을 만들고 그 장비가 요청한 검사기관 소속이라고 응답하도록 stubbing한다
    private CreateReservationRequest rescheduleRequest() {
        CreateReservationRequest request = CreateReservationRequest.builder()
                .slotId(UUID.randomUUID())
                .examInstitutionId(UUID.randomUUID())
                .equipmentId(UUID.randomUUID())
                .examDate(startsAt.plusDays(1))
                .startTime(startsAt.plusDays(1))
                .endTime(startsAt.plusDays(1).plusHours(1))
                .build();
        givenEquipmentOwnedBy(request);
        return request;
    }

    // stubbing 없이 재예약만 호출한다 (여러 스레드에서 동시에 불러도 안전)
    private UUID reschedule(CreateReservationRequest request) {
        return referralService.createRescheduledReservation(userId, Role.REQUESTER, referralId, request).getId();
    }

    private void slotResult(SlotResultType type, UUID reservationId) {
        UUID eventId = UUID.randomUUID();
        eventIds.add(eventId);
        slotResultService.handle(eventId, type, reservationId);
    }

    // ---------- 조회 ----------

    // 삭제 여부와 관계없이 DB에 실제 저장된 attempt_no
    private Integer attemptNo(UUID reservationId) {
        return jdbcTemplate.queryForObject("select attempt_no from p_reservations where id = ?",
                Integer.class, reservationId);
    }

    private ReservationStatus reservationStatus(UUID reservationId) {
        return ReservationStatus.valueOf(jdbcTemplate.queryForObject(
                "select reservation_status from p_reservations where id = ?", String.class, reservationId));
    }

    private ReferralStatus referralStatus() {
        return referralRepository.findById(referralId).orElseThrow().getReferralStatus();
    }

    // 실패 메시지에 남길 이 의뢰의 예약 전체 (소프트 삭제 포함)
    private String reservationRows() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select attempt_no, reservation_status, deleted_at is not null as deleted
                  from p_reservations
                 where referral_id = ?
                 order by created_at
                """, referralId);
        return "p_reservations(referral) = " + rows;
    }

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }
}
