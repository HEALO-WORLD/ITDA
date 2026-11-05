package healoworld.referral_service.application.service;

import healoworld.referral_service.application.event.result.SlotResultType;
import healoworld.referral_service.application.service.IncidentProcessingService.Outcome;
import healoworld.referral_service.domain.entity.IncidentReferral;
import healoworld.referral_service.domain.entity.Referral;
import healoworld.referral_service.domain.entity.Reservation;
import healoworld.referral_service.domain.enums.IncidentProcessingStatus;
import healoworld.referral_service.domain.enums.ReferralStatus;
import healoworld.referral_service.domain.enums.ReservationStatus;
import healoworld.referral_service.domain.enums.RoleType;
import healoworld.referral_service.global.config.QuerydslConfig;
import healoworld.referral_service.global.inbox.ConsumedEventId;
import healoworld.referral_service.global.inbox.ConsumedEventRepository;
import healoworld.referral_service.global.outbox.OutboxEventRepository;
import healoworld.referral_service.global.outbox.SlotCommandOutbox;
import healoworld.referral_service.infrastructure.repository.AffectedReservation;
import healoworld.referral_service.infrastructure.repository.EquipmentIncidentLockRepository;
import healoworld.referral_service.infrastructure.repository.EquipmentIncidentRepository;
import healoworld.referral_service.infrastructure.repository.ExaminationRepository;
import healoworld.referral_service.infrastructure.repository.IncidentReferralRepository;
import healoworld.referral_service.infrastructure.repository.IncidentReservationRepository;
import healoworld.referral_service.infrastructure.repository.NotificationRepository;
import healoworld.referral_service.infrastructure.repository.ReferralHistoryRepository;
import healoworld.referral_service.infrastructure.repository.ReferralRepository;
import healoworld.referral_service.infrastructure.repository.ReservationRepository;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;

/**
 * SlotBooked(예약 확정)와 장비 장애 이벤트의 처리 순서가 어떻든, 장애 구간의 확정 예약이 최종적으로 BLOCKED·NEEDS_RESCHEDULE이 되는지
 * 실제 PostgreSQL(Testcontainers)에서 검증한다.
 * <p>
 * - SlotResultService·EquipmentIncidentService·IncidentProcessingService를 실제로 호출한다 (Kafka Consumer는 거치지 않음)
 * - 동시성 테스트는 한쪽 트랜잭션을 커밋 직전에 멈추고 다른 쪽이 pg_locks에서 잠금을 기다리는 것을 확인한 뒤 커밋해 순서를 고정한다
 * - 각 작업이 실제로 커밋하므로 테스트 트랜잭션을 끄고, 만든 행만 끝날 때 지운다
 */
@DataJpaTest
@Import({
        SlotResultService.class,
        EquipmentIncidentService.class,
        EquipmentIncidentLockRepository.class,
        IncidentProcessingService.class,
        NotificationService.class,
        ExaminationService.class,
        ReservationService.class,
        SlotCommandOutbox.class,
        QuerydslConfig.class,
        SlotBookedIncidentLinkIntegrationTest.JsonConfig.class
})
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class SlotBookedIncidentLinkIntegrationTest extends PostgresContainerSupport {

    @TestConfiguration(proxyBeanMethods = false)
    static class JsonConfig {
        @Bean
        JsonMapper jsonMapper() {
            return JsonMapper.builder().build();
        }
    }

    @Autowired private SlotResultService slotResultService;
    @Autowired private EquipmentIncidentService equipmentIncidentService;
    @Autowired private IncidentProcessingService incidentProcessingService;
    @Autowired private ReservationService reservationService;
    @Autowired private EquipmentIncidentLockRepository equipmentIncidentLockRepository;
    @Autowired private IncidentReservationRepository incidentReservationRepository;
    @Autowired private ReferralRepository referralRepository;
    @Autowired private ReservationRepository reservationRepository;
    @Autowired private EquipmentIncidentRepository equipmentIncidentRepository;
    @Autowired private IncidentReferralRepository incidentReferralRepository;
    @Autowired private ReferralHistoryRepository referralHistoryRepository;
    @Autowired private NotificationRepository notificationRepository;
    @Autowired private ExaminationRepository examinationRepository;
    @Autowired private OutboxEventRepository outboxEventRepository;
    @Autowired private ConsumedEventRepository consumedEventRepository;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbcTemplate;

    // 후속 처리 실패 재현용. 기본은 실제 메서드를 그대로 호출한다
    @MockitoSpyBean private NotificationService notificationService;

    // 같은 이벤트 재전달은 내용도 같아야 하므로 장애의 기관 ID는 고정한다
    private static final UUID EXAM_INSTITUTION_ID = UUID.randomUUID();

    private final List<Booking> bookings = new ArrayList<>();
    private final List<UUID> reservationIds = new ArrayList<>();
    private final List<UUID> incidentIds = new ArrayList<>();
    private final List<UUID> eventIds = new ArrayList<>();
    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    private record Booking(UUID referralId, UUID reservationId, UUID userId, UUID institutionId, UUID equipmentId,
                           LocalDateTime startsAt) {
    }

    @AfterEach
    void cleanUp() {
        executor.shutdownNow();
        List<UUID> referralIds = bookings.stream().map(Booking::referralId).toList();
        notificationRepository.deleteAll(notificationRepository.findAll().stream()
                .filter(n -> referralIds.contains(n.getReferralId())).toList());
        referralIds.forEach(id -> referralHistoryRepository.deleteAll(
                referralHistoryRepository.findAllByReferralIdOrderByOccurredAtAsc(id)));
        outboxEventRepository.deleteAll(outboxEventRepository.findAll().stream()
                .filter(e -> reservationIds.contains(e.getAggregateId())).toList());
        eventIds.forEach(id -> consumedEventRepository.deleteById(new ConsumedEventId(id, SlotResultService.CONSUMER)));
        incidentReferralRepository.deleteAll(incidentReferralRepository.findAll().stream()
                .filter(t -> incidentIds.contains(t.getIncident().getId()) || reservationIds.contains(t.getReservationId()))
                .toList());
        equipmentIncidentRepository.deleteAllById(incidentIds);
        referralIds.forEach(id -> examinationRepository.findByReferralId(id).ifPresent(examinationRepository::delete));
        reservationRepository.deleteAllById(reservationIds);
        referralRepository.deleteAllById(referralIds);
    }

    // ---------- 1·2. 순서 ----------

    @Test
    @DisplayName("정상 순서: SlotBooked 후 장애 이벤트 → 장애 쪽 조회가 작업을 만들고 예약 BLOCKED·의뢰 NEEDS_RESCHEDULE")
    void slotBookedThenIncident() {
        Booking booking = heldBooking();
        slotBooked(booking);

        UUID incidentId = registerIncident(booking);
        assertThat(equipmentIncidentService.createPendingReferrals(incidentId)).isEqualTo(1);

        assertBlockedAfterProcessing(booking);
    }

    @Test
    @DisplayName("역순: 장애 이벤트가 먼저 처리돼 HELD 예약을 건너뛰어도, 이후 SlotBooked가 작업을 만들어 결국 차단된다")
    void incidentThenSlotBooked() {
        Booking booking = heldBooking();
        UUID incidentId = registerIncident(booking);
        assertThat(equipmentIncidentService.createPendingReferrals(incidentId)).isZero();   // HELD라 제외 (기존 누락 지점)

        slotBooked(booking);

        assertThat(tasksOf(booking)).singleElement()
                .satisfies(task -> assertThat(task.getIncident().getId()).isEqualTo(incidentId));
        assertBlockedAfterProcessing(booking);
    }

    // ---------- 3. 동시 처리 ----------

    @Test
    @DisplayName("동시: SlotBooked가 장비 잠금을 먼저 잡으면 장애 쪽 조회는 확정 커밋을 기다렸다가 CONFIRMED 예약을 찾는다")
    void slotBookedLocksFirst() throws Exception {
        Booking booking = heldBooking();

        Ordered<Void, Integer> run = runInOrder(
                () -> {
                    slotBooked(booking);   // 아직 장애가 없어 작업 0건, 장비 잠금을 쥔 채 커밋 대기
                    registerIncident(booking);   // 별도 트랜잭션으로 커밋된다 (register는 장비 잠금을 쓰지 않음)
                    return null;
                },
                () -> equipmentIncidentService.createPendingReferrals(incidentIds.getFirst()));

        assertThat(run.second()).isEqualTo(1);
        assertThat(tasksOf(booking)).hasSize(1);
        assertBlockedAfterProcessing(booking);
    }

    @Test
    @DisplayName("동시: 장애 쪽 조회가 장비 잠금을 먼저 잡으면 SlotBooked는 기다렸다가 그 장애를 보고 작업을 만든다")
    void incidentLookupLocksFirst() throws Exception {
        Booking booking = heldBooking();
        UUID incidentId = registerIncident(booking);

        // createPendingReferrals()의 조회 트랜잭션과 같은 동작(장비 잠금 → 영향 예약 조회)을 커밋 직전에 멈춘다
        Ordered<List<AffectedReservation>, Void> run = runInOrder(
                () -> {
                    equipmentIncidentLockRepository.lock(booking.equipmentId());
                    return incidentReservationRepository.findAffected(booking.equipmentId(),
                            booking.startsAt().minusHours(1), booking.startsAt().plusHours(2));
                },
                () -> {
                    slotBooked(booking);
                    return null;
                });

        assertThat(run.first()).isEmpty();   // 확정이 커밋 전이라 장애 쪽은 이 예약을 보지 못했다
        assertThat(tasksOf(booking)).singleElement()
                .satisfies(task -> assertThat(task.getIncident().getId()).isEqualTo(incidentId));
        // 장애 이벤트가 다시 처리돼도 작업이 늘지 않는다
        assertThat(equipmentIncidentService.createPendingReferrals(incidentId)).isZero();
        assertBlockedAfterProcessing(booking);
    }

    @Test
    @DisplayName("동시: 같은 순간에 시작한 SlotBooked와 장애 처리는 순서와 관계없이 작업을 정확히 1건 만든다 (10회)")
    void simultaneousStart() throws Exception {
        for (int round = 0; round < 10; round++) {
            Booking booking = heldBooking();
            CountDownLatch start = new CountDownLatch(1);
            Future<?> booked = executor.submit(() -> {
                await(start);
                slotBooked(booking);
                return null;
            });
            Future<?> incident = executor.submit(() -> {
                await(start);
                UUID incidentId = registerIncident(booking);
                equipmentIncidentService.createPendingReferrals(incidentId);
                return null;
            });
            start.countDown();
            get(booked);
            get(incident);

            assertThat(tasksOf(booking)).as("round %d", round).hasSize(1);
        }
    }

    // ---------- 4·5. 중복 수신 ----------

    @Test
    @DisplayName("같은 장애 이벤트가 다시 와도 같은 장애·예약 작업은 1건이다")
    void duplicateIncidentEvent() {
        Booking booking = heldBooking();
        UUID sourceEventId = UUID.randomUUID();
        UUID incidentId = registerIncident(booking, sourceEventId);
        slotBooked(booking);

        // 재전달: register는 같은 장애 ID를 돌려주고, createPendingReferrals는 이미 있는 작업을 건너뛴다
        assertThat(registerIncident(booking, sourceEventId)).isEqualTo(incidentId);
        assertThat(equipmentIncidentService.createPendingReferrals(incidentId)).isZero();
        assertThat(equipmentIncidentService.createPendingReferrals(incidentId)).isZero();

        assertThat(tasksOf(booking)).hasSize(1);
    }

    @Test
    @DisplayName("같은 SlotBooked가 다시 와도 작업이 늘지 않는다 (같은 eventId, 이미 확정된 예약)")
    void duplicateSlotBooked() {
        Booking booking = heldBooking();
        registerIncident(booking);
        UUID eventId = UUID.randomUUID();

        slotBooked(booking, eventId);
        slotBooked(booking, eventId);              // 소비 기록으로 건너뜀
        slotBooked(booking, UUID.randomUUID());    // 다른 eventId여도 이미 CONFIRMED라 무시

        assertThat(tasksOf(booking)).hasSize(1);
        assertThat(reservationStatus(booking)).isEqualTo(ReservationStatus.CONFIRMED);
    }

    // ---------- 6. 이미 BLOCKED ----------

    @Test
    @DisplayName("겹치는 장애 2건이 먼저 처리된 뒤 확정되면 작업 2건이 생기고, 두 번째는 ALREADY_BLOCKED로 끝나며 알림은 1건이다")
    void alreadyBlockedBySecondIncident() {
        Booking booking = heldBooking();
        registerIncident(booking);
        registerIncident(booking);
        slotBooked(booking);

        List<IncidentReferral> tasks = tasksOf(booking);
        assertThat(tasks).hasSize(2);
        tasks.forEach(task -> assertThat(incidentProcessingService.process(task.getId())).isEqualTo(Outcome.SUCCEEDED));

        assertThat(tasksOf(booking)).extracting(IncidentReferral::getResultCode)
                .containsExactlyInAnyOrder(IncidentProcessingService.RESCHEDULE_REQUIRED,
                        IncidentProcessingService.ALREADY_BLOCKED);
        assertThat(reservationStatus(booking)).isEqualTo(ReservationStatus.BLOCKED);
        assertThat(notificationsOf(booking)).isEqualTo(1);

        // 이미 BLOCKED인 예약에 늦은 SlotBooked가 와도 새 작업을 만들지 않는다 (기존 보상 ReleaseSlot 경로)
        slotBooked(booking, UUID.randomUUID());
        assertThat(tasksOf(booking)).hasSize(2);
    }

    // ---------- 7. 취소·재예약 ----------

    @Test
    @DisplayName("작업이 처리되기 전에 예약이 취소되면 차단하지 않고 SKIPPED_CANCELED로 끝난다")
    void canceledBeforeProcessing() {
        Booking booking = heldBooking();
        registerIncident(booking);
        slotBooked(booking);

        reservationService.cancelReservation(booking.userId(), booking.institutionId(), RoleType.REQUESTER,
                booking.reservationId());

        IncidentReferral task = tasksOf(booking).getFirst();
        assertThat(incidentProcessingService.process(task.getId())).isEqualTo(Outcome.SUCCEEDED);
        assertThat(resultCode(task)).isEqualTo(IncidentProcessingService.SKIPPED_CANCELED);
        assertThat(reservationStatus(booking)).isEqualTo(ReservationStatus.CANCELED);
        assertThat(referralStatus(booking)).isEqualTo(ReferralStatus.CANCELED);
        assertThat(notificationsOf(booking)).isZero();
    }

    @Test
    @DisplayName("한 장애로 차단된 뒤 새 예약으로 재예약됐다면, 다른 장애의 남은 작업은 새 예약을 바꾸지 않는다 (SKIPPED_NOT_CURRENT)")
    void rebookedBeforeProcessing() {
        Booking booking = heldBooking();
        registerIncident(booking);
        registerIncident(booking);
        slotBooked(booking);
        List<IncidentReferral> tasks = tasksOf(booking);
        assertThat(tasks).hasSize(2);

        // 첫 장애 처리: 예약 BLOCKED, 의뢰 NEEDS_RESCHEDULE
        assertThat(incidentProcessingService.process(tasks.get(0).getId())).isEqualTo(Outcome.SUCCEEDED);
        assertThat(resultCode(tasks.get(0))).isEqualTo(IncidentProcessingService.RESCHEDULE_REQUIRED);

        // 사용자가 다른 장비로 재예약: 같은 의뢰에 새 예약 시도를 만들고 현재 예약을 교체 (의뢰 PENDING)
        UUID newReservationId = tx().execute(status -> {
            Referral referral = referralRepository.findById(booking.referralId()).orElseThrow();
            Reservation next = Reservation.create(referral, 2, UUID.randomUUID(), UUID.randomUUID(),
                    UUID.randomUUID(), booking.startsAt().plusDays(1), booking.startsAt().plusDays(1),
                    booking.startsAt().plusDays(1).plusHours(1));
            reservationRepository.save(next);
            referral.reschedule(next.getId());
            return next.getId();
        });
        reservationIds.add(newReservationId);

        // 두 번째 장애 작업은 이전 예약 기준이라 아무것도 바꾸지 않는다
        assertThat(incidentProcessingService.process(tasks.get(1).getId())).isEqualTo(Outcome.SUCCEEDED);
        assertThat(resultCode(tasks.get(1))).isEqualTo(IncidentProcessingService.SKIPPED_NOT_CURRENT);
        assertThat(reservationStatus(booking)).isEqualTo(ReservationStatus.BLOCKED);
        assertThat(reservationRepository.findById(newReservationId).orElseThrow().getReservationStatus())
                .isEqualTo(ReservationStatus.REQUESTED);
        assertThat(referralStatus(booking)).isEqualTo(ReferralStatus.PENDING);
        assertThat(notificationsOf(booking)).isEqualTo(1);
    }

    // ---------- 8. 실패 후 재시도 ----------

    @Test
    @DisplayName("SlotBooked가 만든 작업의 처리가 실패하면 상태 변경이 롤백되고, 재시도에서 차단된다")
    void processingFailureThenRetry() {
        Booking booking = heldBooking();
        registerIncident(booking);
        slotBooked(booking);
        IncidentReferral task = tasksOf(booking).getFirst();
        doThrow(new IllegalStateException("가상 알림 저장 실패")).doCallRealMethod()
                .when(notificationService).createRescheduleRequired(any(), any());

        LocalDateTime now = LocalDateTime.now();
        assertThat(incidentProcessingService.process(task.getId(), now)).isEqualTo(Outcome.FAILED);
        assertThat(reservationStatus(booking)).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(referralStatus(booking)).isEqualTo(ReferralStatus.CONFIRMED);

        assertThat(incidentProcessingService.process(task.getId(), now.plusMinutes(2))).isEqualTo(Outcome.SUCCEEDED);
        assertThat(reservationStatus(booking)).isEqualTo(ReservationStatus.BLOCKED);
        assertThat(referralStatus(booking)).isEqualTo(ReferralStatus.NEEDS_RESCHEDULE);
        IncidentReferral done = incidentReferralRepository.findById(task.getId()).orElseThrow();
        assertThat(done.getProcessingStatus()).isEqualTo(IncidentProcessingStatus.SUCCEEDED);
        assertThat(done.getRetryCount()).isEqualTo(1);
        assertThat(notificationsOf(booking)).isEqualTo(1);
    }

    // ---------- 9. 회귀 ----------

    @Test
    @DisplayName("겹치는 장애가 없으면 SlotBooked는 기존과 같이 예약·의뢰를 확정하고 검사를 만들며 작업은 생기지 않는다")
    void slotBookedWithoutIncident() {
        Booking booking = heldBooking();
        // 같은 장비라도 시간이 겹치지 않는 장애와 다른 장비의 장애는 영향이 없다
        incidentIds.add(equipmentIncidentService.register(UUID.randomUUID(), booking.equipmentId(), UUID.randomUUID(),
                "가상 사유", LocalDateTime.now(), booking.startsAt().plusHours(1), booking.startsAt().plusHours(2)));
        incidentIds.add(equipmentIncidentService.register(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "가상 사유", LocalDateTime.now(), booking.startsAt(), booking.startsAt().plusHours(1)));

        slotBooked(booking);

        assertThat(reservationStatus(booking)).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(referralStatus(booking)).isEqualTo(ReferralStatus.CONFIRMED);
        assertThat(examinationRepository.findByReferralId(booking.referralId())).isPresent();
        assertThat(tasksOf(booking)).isEmpty();
    }

    // ---------- 도우미 ----------

    private void assertBlockedAfterProcessing(Booking booking) {
        List<IncidentReferral> tasks = tasksOf(booking);
        assertThat(tasks).hasSize(1);
        assertThat(incidentProcessingService.process(tasks.getFirst().getId())).isEqualTo(Outcome.SUCCEEDED);
        assertThat(resultCode(tasks.getFirst())).isEqualTo(IncidentProcessingService.RESCHEDULE_REQUIRED);
        assertThat(reservationStatus(booking)).isEqualTo(ReservationStatus.BLOCKED);
        assertThat(referralStatus(booking)).isEqualTo(ReferralStatus.NEEDS_RESCHEDULE);
        assertThat(notificationsOf(booking)).isEqualTo(1);
    }

    // HOLD까지 끝난 첫 예약 시도 (의뢰 PENDING, 예약 HELD). 취소 가능하도록 3일 뒤 14시로 잡는다
    private Booking heldBooking() {
        LocalDateTime startsAt = LocalDateTime.now().plusDays(3).withHour(14).withMinute(0).withSecond(0).withNano(0);
        UUID userId = UUID.randomUUID();
        UUID institutionId = UUID.randomUUID();
        UUID equipmentId = UUID.randomUUID();
        Booking booking = tx().execute(status -> {
            Referral referral = referralRepository.save(Referral.create(institutionId, userId, UUID.randomUUID(),
                    "가상환자", UUID.randomUUID()));
            Reservation reservation = Reservation.create(referral, 1, UUID.randomUUID(), UUID.randomUUID(),
                    equipmentId, startsAt, startsAt, startsAt.plusHours(1));
            reservation.hold();
            reservationRepository.save(reservation);
            referral.assignReservation(reservation.getId());
            return new Booking(referral.getId(), reservation.getId(), userId, institutionId, equipmentId, startsAt);
        });
        bookings.add(booking);
        reservationIds.add(booking.reservationId());
        return booking;
    }

    private void slotBooked(Booking booking) {
        slotBooked(booking, UUID.randomUUID());
    }

    private void slotBooked(Booking booking, UUID eventId) {
        synchronized (eventIds) {
            eventIds.add(eventId);
        }
        slotResultService.handle(eventId, SlotResultType.SLOT_BOOKED, booking.reservationId());
    }

    // 예약(14~15시)을 덮는 장애 13~16시
    private UUID registerIncident(Booking booking) {
        return registerIncident(booking, UUID.randomUUID());
    }

    private UUID registerIncident(Booking booking, UUID sourceEventId) {
        UUID id = equipmentIncidentService.register(sourceEventId, booking.equipmentId(), EXAM_INSTITUTION_ID,
                "가상 장애 사유", booking.startsAt().minusDays(1), booking.startsAt().minusHours(1),
                booking.startsAt().plusHours(2));
        synchronized (incidentIds) {
            if (!incidentIds.contains(id)) {
                incidentIds.add(id);
            }
        }
        return id;
    }

    private List<IncidentReferral> tasksOf(Booking booking) {
        return incidentReferralRepository.findAll().stream()
                .filter(task -> task.getReservationId().equals(booking.reservationId()))
                .toList();
    }

    private String resultCode(IncidentReferral task) {
        return incidentReferralRepository.findById(task.getId()).orElseThrow().getResultCode();
    }

    private ReservationStatus reservationStatus(Booking booking) {
        return reservationRepository.findById(booking.reservationId()).orElseThrow().getReservationStatus();
    }

    private ReferralStatus referralStatus(Booking booking) {
        return referralRepository.findById(booking.referralId()).orElseThrow().getReferralStatus();
    }

    private long notificationsOf(Booking booking) {
        return notificationRepository.findAll().stream()
                .filter(n -> booking.referralId().equals(n.getReferralId()))
                .count();
    }

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }

    record Ordered<A, B>(A first, B second) {
    }

    // first를 트랜잭션 안에서 실행하고 커밋을 미룬 채, second가 잠금을 기다리는 것을 확인한 뒤 커밋한다
    private <A, B> Ordered<A, B> runInOrder(Supplier<A> first, Supplier<B> second) throws Exception {
        CountDownLatch firstDone = new CountDownLatch(1);
        CountDownLatch commitFirst = new CountDownLatch(1);
        Future<A> firstResult = executor.submit(() -> tx().execute(status -> {
            A result = first.get();
            firstDone.countDown();
            await(commitFirst);
            return result;
        }));
        assertThat(firstDone.await(10, TimeUnit.SECONDS)).as("첫 번째 작업이 끝나지 않았다").isTrue();

        Future<B> secondResult = executor.submit(second::get);
        waitUntilSomeoneWaitsForLock();
        assertThat(secondResult.isDone()).as("두 번째 작업은 잠금을 기다려야 한다").isFalse();

        commitFirst.countDown();
        return new Ordered<>(get(firstResult), get(secondResult));
    }

    private void waitUntilSomeoneWaitsForLock() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbcTemplate.queryForObject("select count(*) from pg_locks where not granted", Integer.class);
            if (waiting != null && waiting > 0) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("두 번째 작업이 잠금 대기에 들어가지 않았다");
    }

    private static <T> T get(Future<T> future) throws Exception {
        try {
            return future.get(15, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            throw (Exception) e.getCause();
        }
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
}
