package healoworld.slot_service.application;

import healoworld.slot_service.application.dto.BookOutcome;
import healoworld.slot_service.application.dto.EquipmentDowntimeRegistration;
import healoworld.slot_service.application.dto.EquipmentDowntimeRegistration.ConflictedSlot;
import healoworld.slot_service.application.dto.HoldResult;
import healoworld.slot_service.application.service.EquipmentDowntimeWriter;
import healoworld.slot_service.application.service.SlotBookService;
import healoworld.slot_service.application.service.SlotCreateWriter;
import healoworld.slot_service.application.service.SlotHoldService;
import healoworld.slot_service.domain.entity.Slot;
import healoworld.slot_service.domain.enums.SlotStatus;
import healoworld.slot_service.global.exception.BaseException;
import healoworld.slot_service.global.exception.ErrorCode;
import healoworld.slot_service.global.outbox.OutboxEventRepository;
import healoworld.slot_service.infrastructure.repository.EquipmentDowntimeRepository;
import healoworld.slot_service.infrastructure.repository.SlotRepository;
import healoworld.slot_service.infrastructure.repository.SlotStatusHistoryRepository;
import healoworld.slot_service.support.FixedClockConfig;
import healoworld.slot_service.support.PostgresContainerSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 장비 중단 등록과 HOLD·BOOK·슬롯 생성·중복 등록이 겹칠 때의 결과를 실제 Postgres 잠금으로 검증한다
//
// 순서를 확정하려고 먼저 실행할 쪽을 바깥 트랜잭션(TransactionTemplate)에 넣고 커밋 직전에 멈춘다.
// 그다음 두 번째 요청을 다른 스레드에서 시작하고, pg_locks에서 그 요청이 실제로 잠금을 기다리는 것을 확인한 뒤
// 첫 번째 트랜잭션을 커밋한다. 그래서 "먼저 잠근 쪽 → 기다린 쪽" 순서가 매번 같게 재현된다
@SpringBootTest
@ActiveProfiles("test")
@Import(FixedClockConfig.class)
class EquipmentDowntimeConcurrencyTest extends PostgresContainerSupport {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 10);
    private static final LocalDateTime DOWN_START = DAY.atTime(10, 0);
    private static final LocalDateTime DOWN_END = DAY.atTime(12, 0);
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID INSTITUTION_ID = UUID.randomUUID();

    @Autowired private EquipmentDowntimeWriter equipmentDowntimeWriter;
    @Autowired private SlotHoldService slotHoldService;
    @Autowired private SlotBookService slotBookService;
    @Autowired private SlotCreateWriter slotCreateWriter;
    @Autowired private SlotRepository slotRepository;
    @Autowired private SlotStatusHistoryRepository slotStatusHistoryRepository;
    @Autowired private EquipmentDowntimeRepository equipmentDowntimeRepository;
    @Autowired private OutboxEventRepository outboxEventRepository;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbcTemplate;

    private final UUID equipmentId = UUID.randomUUID();
    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
        outboxEventRepository.deleteAllInBatch();
        slotStatusHistoryRepository.deleteAllInBatch();
        slotRepository.deleteAllInBatch();
        equipmentDowntimeRepository.deleteAllInBatch();
    }

    // ---------- 중단 등록 vs HOLD ----------

    @Test
    void HOLD가_먼저_잠그면_중단_등록은_기다린_뒤_HELD를_충돌로_표시한다() throws Exception {
        UUID reservationId = UUID.randomUUID();
        Slot slot = saveAvailable();

        Ordered<HoldResult, EquipmentDowntimeRegistration> run = runInOrder(
                () -> slotHoldService.hold(slot.getId(), reservationId, UUID.randomUUID()),
                this::register);

        assertThat(run.first().isSuccess()).isTrue();
        assertThat(run.second().blockedSlotCount()).isZero();
        assertThat(run.second().conflictedSlots())
                .containsExactly(new ConflictedSlot(slot.getId(), SlotStatus.HELD, reservationId));
        Slot saved = reload(slot);
        assertThat(saved.getStatus()).isEqualTo(SlotStatus.HELD);
        assertThat(saved.getEquipmentDowntimeId()).isEqualTo(run.second().downtime().getId());

        // 이어서 도착한 확정은 거부된다
        assertThat(slotBookService.book(slot.getId(), reservationId, UUID.randomUUID())).isEqualTo(BookOutcome.FAILED);
    }

    @Test
    void 중단_등록이_먼저_잠그면_HOLD는_기다린_뒤_실패한다() throws Exception {
        Slot slot = saveAvailable();

        Ordered<EquipmentDowntimeRegistration, HoldResult> run = runInOrder(
                this::register,
                () -> slotHoldService.hold(slot.getId(), UUID.randomUUID(), UUID.randomUUID()));

        assertThat(run.first().blockedSlotCount()).isEqualTo(1);
        assertThat(run.second().isSuccess()).isFalse();
        Slot saved = reload(slot);
        assertThat(saved.getStatus()).isEqualTo(SlotStatus.UNAVAILABLE);
        assertThat(saved.getReservationId()).isNull();
    }

    // ---------- 중단 등록 vs BOOK ----------

    @Test
    void BOOK이_먼저_잠그면_중단_등록은_기다린_뒤_BOOKED를_충돌로_표시한다() throws Exception {
        UUID reservationId = UUID.randomUUID();
        Slot slot = saveHeld(reservationId);

        Ordered<BookOutcome, EquipmentDowntimeRegistration> run = runInOrder(
                () -> slotBookService.book(slot.getId(), reservationId, UUID.randomUUID()),
                this::register);

        assertThat(run.first()).isEqualTo(BookOutcome.BOOKED);
        assertThat(run.second().conflictedSlots())
                .containsExactly(new ConflictedSlot(slot.getId(), SlotStatus.BOOKED, reservationId));
        Slot saved = reload(slot);
        assertThat(saved.getStatus()).isEqualTo(SlotStatus.BOOKED);
        assertThat(saved.getReservationId()).isEqualTo(reservationId);
        assertThat(saved.getEquipmentDowntimeId()).isEqualTo(run.second().downtime().getId());
    }

    @Test
    void 중단_등록이_먼저_잠그면_BOOK은_기다린_뒤_확정되지_않는다() throws Exception {
        UUID reservationId = UUID.randomUUID();
        Slot slot = saveHeld(reservationId);

        Ordered<EquipmentDowntimeRegistration, BookOutcome> run = runInOrder(
                this::register,
                () -> slotBookService.book(slot.getId(), reservationId, UUID.randomUUID()));

        assertThat(run.first().conflictedSlots())
                .containsExactly(new ConflictedSlot(slot.getId(), SlotStatus.HELD, reservationId));
        assertThat(run.second()).isEqualTo(BookOutcome.FAILED);
        Slot saved = reload(slot);
        assertThat(saved.getStatus()).isEqualTo(SlotStatus.HELD);   // 상태·예약은 바꾸지 않는다 (Referral 보상 대상)
        assertThat(saved.getReservationId()).isEqualTo(reservationId);
    }

    // ---------- 중단 등록 vs 슬롯 생성, 중단 중복 등록 ----------

    @Test
    void 중단_등록_중에_같은_날짜_슬롯을_생성하면_기다린_뒤_409로_거부된다() throws Exception {
        Ordered<EquipmentDowntimeRegistration, Throwable> run = runInOrder(
                this::register,
                () -> failureOf(() -> slotCreateWriter.createSlot(equipmentId, UUID.randomUUID(), DAY,
                        LocalTime.of(11, 0), LocalTime.of(11, 30))));

        assertThat(run.second()).isInstanceOf(BaseException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.SLOT_IN_DOWNTIME);
        assertThat(slotRepository.count()).isZero();
    }

    @Test
    void 겹치는_중단을_동시에_등록하면_하나만_성공하고_나머지는_409다() throws Exception {
        Ordered<EquipmentDowntimeRegistration, Throwable> run = runInOrder(
                this::register,
                () -> failureOf(() -> equipmentDowntimeWriter.register(equipmentId, INSTITUTION_ID,
                        DOWN_START.plusHours(1), DOWN_END.plusHours(1), "가상 사유", USER_ID)));

        assertThat(run.second()).isInstanceOf(BaseException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.DOWNTIME_OVERLAP);
        assertThat(equipmentDowntimeRepository.count()).isEqualTo(1);
    }

    @Test
    void 여러_날짜에_걸친_중단끼리_동시에_등록해도_교착_없이_하나만_성공한다() throws Exception {
        // 두 요청 모두 11/10, 11/11 날짜 잠금이 필요하다. 날짜 오름차순으로 잠그므로 교착이 생기지 않는다
        Ordered<EquipmentDowntimeRegistration, Throwable> run = runInOrder(
                () -> equipmentDowntimeWriter.register(equipmentId, INSTITUTION_ID, DAY.atTime(20, 0), DAY.plusDays(1).atTime(9, 0),
                        "가상 사유", USER_ID),
                () -> failureOf(() -> equipmentDowntimeWriter.register(equipmentId, INSTITUTION_ID, DAY.atTime(8, 0),
                        DAY.plusDays(1).atTime(23, 0), "가상 사유", USER_ID)));

        assertThat(run.second()).isInstanceOf(BaseException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.DOWNTIME_OVERLAP);
    }

    @Test
    void 동시에_시작한_겹치는_중단_등록은_하나만_저장된다() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        Callable<Boolean> task = () -> {
            start.await();
            try {
                register();
                return true;
            } catch (BaseException e) {
                return false;
            }
        };
        Future<Boolean> a = executor.submit(task);
        Future<Boolean> b = executor.submit(task);
        start.countDown();

        assertThat(a.get(10, TimeUnit.SECONDS) ^ b.get(10, TimeUnit.SECONDS)).isTrue();
        assertThat(equipmentDowntimeRepository.count()).isEqualTo(1);
    }

    // ---------- 도우미 ----------

    record Ordered<A, B>(A first, B second) {
    }

    // first를 트랜잭션 안에서 실행하고 커밋을 미룬 채, second가 잠금을 기다리는 것을 확인한 뒤 커밋한다
    private <A, B> Ordered<A, B> runInOrder(Supplier<A> first, Supplier<B> second) throws Exception {
        CountDownLatch firstDone = new CountDownLatch(1);
        CountDownLatch commitFirst = new CountDownLatch(1);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        Future<A> firstResult = executor.submit(() -> tx.execute(status -> {
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

    // 다른 세션이 잠금을 얻지 못하고 기다리는 상태(pg_locks.granted = false)가 될 때까지 기다린다
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

    private Throwable failureOf(Runnable action) {
        try {
            action.run();
            return null;
        } catch (RuntimeException e) {
            return e;
        }
    }

    private static <T> T get(Future<T> future) throws Exception {
        try {
            return future.get(10, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            throw (Exception) e.getCause();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("커밋 신호를 받지 못했다");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private EquipmentDowntimeRegistration register() {
        return equipmentDowntimeWriter.register(equipmentId, INSTITUTION_ID, DOWN_START, DOWN_END, "가상 사유", USER_ID);
    }

    private Slot saveAvailable() {
        return slotRepository.saveAndFlush(Slot.create(equipmentId, UUID.randomUUID(), DAY,
                LocalTime.of(10, 30), LocalTime.of(11, 0)));
    }

    private Slot reload(Slot slot) {
        return slotRepository.findById(slot.getId()).orElseThrow();
    }

    private Slot saveHeld(UUID reservationId) {
        Slot slot = Slot.create(equipmentId, UUID.randomUUID(), DAY, LocalTime.of(10, 30), LocalTime.of(11, 0));
        ReflectionTestUtils.setField(slot, "status", SlotStatus.HELD);
        ReflectionTestUtils.setField(slot, "reservationId", reservationId);
        ReflectionTestUtils.setField(slot, "heldUntil", FixedClockConfig.NOW.plusMinutes(3));
        return slotRepository.saveAndFlush(slot);
    }
}
