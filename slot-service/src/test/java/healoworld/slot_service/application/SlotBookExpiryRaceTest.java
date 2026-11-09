package healoworld.slot_service.application;

import healoworld.slot_service.support.PostgresContainerSupport;

import healoworld.slot_service.domain.entity.Slot;
import healoworld.slot_service.domain.enums.SlotStatus;
import healoworld.slot_service.infrastructure.repository.SlotRepository;
import healoworld.slot_service.infrastructure.repository.SlotStatusHistoryRepository;
import healoworld.slot_service.support.fixture.SlotFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// 같은 슬롯에 BookSlot 확정(bookIfHeld)과 HOLD 만료(expireHoldIfDue)가 동시에 실행될 때 하나만 성공하는지 검증한다.
// 서비스는 Clock 하나를 공유해 두 조건(held_until > now / held_until <= now)을 동시에 만족시킬 수 없으므로,
// 시각을 따로 줄 수 있는 레포지토리 UPDATE를 직접 경쟁시킨다.
// 확정 쪽은 만료 1초 전, 만료 쪽은 만료 1초 후 시각을 써서 각자 조건을 만족하는 상황(인스턴스 간 시계 차이, 경계 시각)을 만든다.
@SpringBootTest
@ActiveProfiles("test")
class SlotBookExpiryRaceTest extends PostgresContainerSupport {

    private static final LocalDateTime HELD_UNTIL = LocalDateTime.of(2026, 10, 30, 10, 5);
    private static final LocalDateTime BOOK_NOW = HELD_UNTIL.minusSeconds(1);     // 확정 조건 held_until > now 만족
    private static final LocalDateTime EXPIRE_NOW = HELD_UNTIL.plusSeconds(1);    // 만료 조건 held_until <= now 만족
    private static final int ROUNDS = 100;
    private static final long WAIT_SECONDS = 10;

    @Autowired
    private SlotRepository slotRepository;

    @Autowired
    private SlotStatusHistoryRepository slotStatusHistoryRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate tx;
    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
        executor = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
        slotStatusHistoryRepository.deleteAllInBatch();
        slotRepository.deleteAllInBatch();
    }

    @Test
    void 확정이_먼저_행을_잠그면_만료는_대기했다가_0행이_되고_BOOKED가_유지된다() throws Exception {
        UUID reservationId = UUID.randomUUID();
        Slot slot = slotRepository.saveAndFlush(SlotFixture.held(reservationId, HELD_UNTIL));

        CountDownLatch firstUpdated = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);

        // 확정 UPDATE 후 커밋을 미뤄 행 잠금을 쥐고 있는다
        Future<Integer> book = executor.submit(() -> tx.execute(status -> {
            int updated = book(slot.getId(), reservationId);
            firstUpdated.countDown();
            await(releaseFirst);
            return updated;
        }));
        assertThat(firstUpdated.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();

        Future<Integer> expire = executor.submit(() -> tx.execute(status -> expire(slot.getId(), reservationId)));

        // 확정 트랜잭션이 커밋되기 전까지 만료 UPDATE는 행 잠금을 기다린다
        assertThatThrownBy(() -> expire.get(500, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);

        releaseFirst.countDown();

        assertThat(book.get(WAIT_SECONDS, TimeUnit.SECONDS)).isEqualTo(1);
        assertThat(expire.get(WAIT_SECONDS, TimeUnit.SECONDS)).isZero();   // 커밋 후 WHERE 재평가: status가 HELD가 아님

        Slot saved = slotRepository.findById(slot.getId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(SlotStatus.BOOKED);
        assertThat(saved.getReservationId()).isEqualTo(reservationId);
    }

    @Test
    void 만료가_먼저_행을_잠그면_확정은_대기했다가_0행이_되고_AVAILABLE이_유지된다() throws Exception {
        UUID reservationId = UUID.randomUUID();
        Slot slot = slotRepository.saveAndFlush(SlotFixture.held(reservationId, HELD_UNTIL));

        CountDownLatch firstUpdated = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);

        // 만료 UPDATE 후 커밋을 미뤄 행 잠금을 쥐고 있는다
        Future<Integer> expire = executor.submit(() -> tx.execute(status -> {
            int updated = expire(slot.getId(), reservationId);
            firstUpdated.countDown();
            await(releaseFirst);
            return updated;
        }));
        assertThat(firstUpdated.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();

        Future<Integer> book = executor.submit(() -> tx.execute(status -> book(slot.getId(), reservationId)));

        // 만료 트랜잭션이 커밋되기 전까지 확정 UPDATE는 행 잠금을 기다린다
        assertThatThrownBy(() -> book.get(500, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);

        releaseFirst.countDown();

        assertThat(expire.get(WAIT_SECONDS, TimeUnit.SECONDS)).isEqualTo(1);
        assertThat(book.get(WAIT_SECONDS, TimeUnit.SECONDS)).isZero();   // 커밋 후 WHERE 재평가: status·reservationId 불일치

        Slot saved = slotRepository.findById(slot.getId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(SlotStatus.AVAILABLE);
        assertThat(saved.getReservationId()).isNull();
        assertThat(saved.getHeldUntil()).isNull();
    }

    @Test
    void 확정과_만료를_동시에_출발시키면_매_회차_정확히_하나만_성공한다() throws Exception {
        int bookWins = 0;
        int expireWins = 0;

        for (int round = 0; round < ROUNDS; round++) {
            UUID reservationId = UUID.randomUUID();
            Slot slot = slotRepository.saveAndFlush(SlotFixture.held(reservationId, HELD_UNTIL));

            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);

            Future<Integer> book = executor.submit(() -> {
                ready.countDown();
                start.await();
                return tx.execute(status -> book(slot.getId(), reservationId));
            });
            Future<Integer> expire = executor.submit(() -> {
                ready.countDown();
                start.await();
                return tx.execute(status -> expire(slot.getId(), reservationId));
            });

            assertThat(ready.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
            start.countDown();   // 동시에 출발

            int booked = book.get(WAIT_SECONDS, TimeUnit.SECONDS);
            int expired = expire.get(WAIT_SECONDS, TimeUnit.SECONDS);

            assertThat(booked + expired).as("%d회차: 둘 중 정확히 하나만 성공해야 한다", round).isEqualTo(1);

            Slot saved = slotRepository.findById(slot.getId()).orElseThrow();
            if (booked == 1) {
                bookWins++;
                assertThat(saved.getStatus()).as("%d회차: 확정 승리", round).isEqualTo(SlotStatus.BOOKED);
                assertThat(saved.getReservationId()).isEqualTo(reservationId);
            } else {
                expireWins++;
                assertThat(saved.getStatus()).as("%d회차: 만료 승리", round).isEqualTo(SlotStatus.AVAILABLE);
                assertThat(saved.getReservationId()).isNull();
            }
        }

        System.out.printf("확정·만료 경쟁 %d회: 확정 승 %d, 만료 승 %d%n", ROUNDS, bookWins, expireWins);
    }

    private int book(UUID slotId, UUID reservationId) {
        return slotRepository.bookIfHeld(slotId, reservationId, BOOK_NOW, SlotStatus.HELD, SlotStatus.BOOKED);
    }

    private int expire(UUID slotId, UUID reservationId) {
        return slotRepository.expireHoldIfDue(slotId, reservationId, EXPIRE_NOW, SlotStatus.HELD, SlotStatus.AVAILABLE);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(WAIT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("대기 시간 초과");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("대기 중 인터럽트", e);
        }
    }
}
