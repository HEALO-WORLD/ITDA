package healoworld.slot_service.application;

import healoworld.slot_service.application.event.command.BookSlotEvent;
import healoworld.slot_service.application.event.command.HoldRequestedEvent;
import healoworld.slot_service.application.service.SlotCommandHandler;
import healoworld.slot_service.application.service.SlotHoldExpiryService;
import healoworld.slot_service.domain.entity.Slot;
import healoworld.slot_service.global.inbox.ConsumedEventRepository;
import healoworld.slot_service.global.outbox.OutboxEventRepository;
import healoworld.slot_service.global.outbox.OutboxPublisher;
import healoworld.slot_service.infrastructure.repository.SlotRepository;
import healoworld.slot_service.infrastructure.repository.SlotStatusHistoryRepository;
import healoworld.slot_service.support.FixedClockConfig;
import healoworld.slot_service.support.PostgresContainerSupport;
import healoworld.slot_service.support.fixture.SlotFixture;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// slot.hold, slot.book, slot.hold.expired 카운터가 처리 결과별로 커밋 후 한 번씩 세지는지 확인한다
// 카운터는 컨텍스트를 공유하는 다른 테스트에서도 오르므로 처리 전후 차이로 비교한다
@SpringBootTest
@ActiveProfiles("test")
@Import(FixedClockConfig.class)
class SlotDomainMetricsTest extends PostgresContainerSupport {

    @Autowired
    private SlotCommandHandler slotCommandHandler;

    @Autowired
    private SlotHoldExpiryService slotHoldExpiryService;

    @Autowired
    private SlotRepository slotRepository;

    @Autowired
    private SlotStatusHistoryRepository slotStatusHistoryRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private ConsumedEventRepository consumedEventRepository;

    @Autowired
    private MeterRegistry meterRegistry;

    // 테스트 중 스케줄러가 로컬 Kafka로 Outbox를 실제 발행하지 않도록 막는다
    @MockitoBean
    private OutboxPublisher outboxPublisher;

    @AfterEach
    void tearDown() {
        outboxEventRepository.deleteAllInBatch();
        consumedEventRepository.deleteAllInBatch();
        slotStatusHistoryRepository.deleteAllInBatch();
        slotRepository.deleteAllInBatch();
    }

    @Test
    void HOLD_결과를_success_idempotent_conflict_not_found로_센다() {
        Slot available = slotRepository.saveAndFlush(SlotFixture.available());
        UUID heldReservationId = UUID.randomUUID();
        Slot heldByMe = slotRepository.saveAndFlush(SlotFixture.held(heldReservationId, FixedClockConfig.NOW.plusMinutes(3)));
        Slot heldByOther = slotRepository.saveAndFlush(SlotFixture.held());
        double success = count("slot.hold", "success");
        double idempotent = count("slot.hold", "idempotent");
        double conflict = count("slot.hold", "conflict");
        double notFound = count("slot.hold", "not_found");

        slotCommandHandler.handleHoldRequested(holdRequested(available.getId(), UUID.randomUUID()));
        slotCommandHandler.handleHoldRequested(holdRequested(heldByMe.getId(), heldReservationId));
        slotCommandHandler.handleHoldRequested(holdRequested(heldByOther.getId(), UUID.randomUUID()));
        slotCommandHandler.handleHoldRequested(holdRequested(UUID.randomUUID(), UUID.randomUUID()));

        assertThat(count("slot.hold", "success")).isEqualTo(success + 1);
        assertThat(count("slot.hold", "idempotent")).isEqualTo(idempotent + 1);
        assertThat(count("slot.hold", "conflict")).isEqualTo(conflict + 1);
        assertThat(count("slot.hold", "not_found")).isEqualTo(notFound + 1);
    }

    @Test
    void 시작_시각이_지난_슬롯의_HOLD_실패는_conflict가_아니라_expired로_센다() {
        Slot passed = slotRepository.saveAndFlush(Slot.create(UUID.randomUUID(), UUID.randomUUID(),
                FixedClockConfig.NOW.toLocalDate().minusDays(1), LocalTime.of(9, 0), LocalTime.of(9, 30)));
        double expired = count("slot.hold", "expired");
        double conflict = count("slot.hold", "conflict");

        slotCommandHandler.handleHoldRequested(holdRequested(passed.getId(), UUID.randomUUID()));

        assertThat(count("slot.hold", "expired")).isEqualTo(expired + 1);
        assertThat(count("slot.hold", "conflict")).isEqualTo(conflict);
    }

    @Test
    void 같은_HoldRequested_이벤트를_두_번_받으면_한_번만_센다() {
        Slot slot = slotRepository.saveAndFlush(SlotFixture.available());
        HoldRequestedEvent request = holdRequested(slot.getId(), UUID.randomUUID());
        double before = count("slot.hold", "success");

        slotCommandHandler.handleHoldRequested(request);
        slotCommandHandler.handleHoldRequested(request);

        assertThat(count("slot.hold", "success")).isEqualTo(before + 1);
    }

    @Test
    void BookSlot_결과를_success_idempotent_failed로_센다() {
        UUID heldReservationId = UUID.randomUUID();
        Slot held = slotRepository.saveAndFlush(SlotFixture.held(heldReservationId, FixedClockConfig.NOW.plusMinutes(3)));
        UUID bookedReservationId = UUID.randomUUID();
        Slot booked = slotRepository.saveAndFlush(SlotFixture.booked(bookedReservationId));
        Slot heldByOther = slotRepository.saveAndFlush(SlotFixture.held());
        double success = count("slot.book", "success");
        double idempotent = count("slot.book", "idempotent");
        double failed = count("slot.book", "failed");

        slotCommandHandler.handleBookSlot(bookSlot(held.getId(), heldReservationId));
        slotCommandHandler.handleBookSlot(bookSlot(booked.getId(), bookedReservationId));
        slotCommandHandler.handleBookSlot(bookSlot(heldByOther.getId(), UUID.randomUUID()));

        assertThat(count("slot.book", "success")).isEqualTo(success + 1);
        assertThat(count("slot.book", "idempotent")).isEqualTo(idempotent + 1);
        assertThat(count("slot.book", "failed")).isEqualTo(failed + 1);
    }

    @Test
    void 만료된_HOLD에_BookSlot이_오면_hold_expired와_만료_건수를_함께_센다() {
        UUID reservationId = UUID.randomUUID();
        Slot slot = slotRepository.saveAndFlush(SlotFixture.held(reservationId, FixedClockConfig.NOW.minusSeconds(1)));
        double bookExpired = count("slot.book", "hold_expired");
        double expired = expiredCount();

        slotCommandHandler.handleBookSlot(bookSlot(slot.getId(), reservationId));

        assertThat(count("slot.book", "hold_expired")).isEqualTo(bookExpired + 1);
        assertThat(expiredCount()).isEqualTo(expired + 1);
    }

    @Test
    void 스케줄러_만료는_만료_건수만_세고_대상이_아니면_세지_않는다() {
        UUID reservationId = UUID.randomUUID();
        Slot slot = slotRepository.saveAndFlush(SlotFixture.held(reservationId, FixedClockConfig.NOW.minusSeconds(1)));
        double before = expiredCount();

        slotHoldExpiryService.expire(slot.getId(), reservationId, slot.getHeldUntil());
        slotHoldExpiryService.expire(slot.getId(), reservationId, slot.getHeldUntil());   // 이미 만료됨

        assertThat(expiredCount()).isEqualTo(before + 1);
    }

    private double count(String name, String result) {
        Counter counter = meterRegistry.find(name).tag("result", result).counter();
        return counter == null ? 0 : counter.count();
    }

    private double expiredCount() {
        Counter counter = meterRegistry.find("slot.hold.expired").counter();
        return counter == null ? 0 : counter.count();
    }

    private HoldRequestedEvent holdRequested(UUID slotId, UUID reservationId) {
        return new HoldRequestedEvent(UUID.randomUUID(), HoldRequestedEvent.TYPE, FixedClockConfig.NOW, slotId, reservationId);
    }

    private BookSlotEvent bookSlot(UUID slotId, UUID reservationId) {
        return new BookSlotEvent(UUID.randomUUID(), BookSlotEvent.TYPE, FixedClockConfig.NOW, slotId, reservationId);
    }
}
