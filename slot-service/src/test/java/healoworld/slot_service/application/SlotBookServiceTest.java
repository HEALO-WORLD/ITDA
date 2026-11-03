package healoworld.slot_service.application;

import healoworld.slot_service.application.dto.BookOutcome;
import healoworld.slot_service.application.service.SlotBookService;
import healoworld.slot_service.domain.entity.Slot;
import healoworld.slot_service.domain.enums.SlotStatus;
import healoworld.slot_service.global.outbox.OutboxEvent;
import healoworld.slot_service.global.outbox.OutboxEventRepository;
import healoworld.slot_service.infrastructure.repository.SlotRepository;
import healoworld.slot_service.support.FixedClockConfig;
import healoworld.slot_service.support.fixture.SlotFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// 고정 시각 FixedClockConfig.NOW(2026-10-30T10:00) 기준으로 예약 확정을 검증한다
@SpringBootTest
@ActiveProfiles("test")
@Import(FixedClockConfig.class)
class SlotBookServiceTest {

    private static final LocalDateTime NOW = FixedClockConfig.NOW;

    @Autowired
    private SlotBookService slotBookService;

    @Autowired
    private SlotRepository slotRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @AfterEach
    void tearDown() {
        outboxEventRepository.deleteAllInBatch();
        slotRepository.deleteAllInBatch();
    }

    @Test
    void 같은_예약으로_만료_전_HELD면_BOOKED로_확정한다() {
        UUID reservationId = UUID.randomUUID();
        Slot slot = slotRepository.saveAndFlush(SlotFixture.held(reservationId, NOW.plusMinutes(3)));

        BookOutcome outcome = slotBookService.book(slot.getId(), reservationId);

        assertThat(outcome).isEqualTo(BookOutcome.BOOKED);
        Slot saved = slotRepository.findById(slot.getId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(SlotStatus.BOOKED);
        assertThat(saved.getReservationId()).isEqualTo(reservationId);
        assertThat(saved.getHeldUntil()).isNull();
        assertThat(outboxEventRepository.findAll()).isEmpty();   // 결과 이벤트 기록은 핸들러 책임
    }

    @Test
    void 같은_예약으로_이미_BOOKED면_ALREADY_BOOKED를_반환하고_변경하지_않는다() {
        UUID reservationId = UUID.randomUUID();
        Slot slot = slotRepository.saveAndFlush(SlotFixture.booked(reservationId));

        BookOutcome outcome = slotBookService.book(slot.getId(), reservationId);

        assertThat(outcome).isEqualTo(BookOutcome.ALREADY_BOOKED);
        Slot saved = slotRepository.findById(slot.getId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(SlotStatus.BOOKED);
        assertThat(saved.getReservationId()).isEqualTo(reservationId);
    }

    @Test
    void 만료시각이_지난_HOLD는_확정하지_않고_만료_처리한다() {
        UUID reservationId = UUID.randomUUID();
        LocalDateTime heldUntil = NOW.minusSeconds(1);
        Slot slot = slotRepository.saveAndFlush(SlotFixture.held(reservationId, heldUntil));

        BookOutcome outcome = slotBookService.book(slot.getId(), reservationId);

        assertThat(outcome).isEqualTo(BookOutcome.HOLD_EXPIRED);
        Slot saved = slotRepository.findById(slot.getId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(SlotStatus.AVAILABLE);
        assertThat(saved.getReservationId()).isNull();

        List<OutboxEvent> events = outboxEventRepository.findAll();
        assertThat(events).hasSize(1);
        assertThat(events.getFirst().getEventType()).isEqualTo("HoldExpired");
    }

    @Test
    void 만료시각과_현재시각이_같으면_확정하지_않는다() {
        UUID reservationId = UUID.randomUUID();
        Slot slot = slotRepository.saveAndFlush(SlotFixture.held(reservationId, NOW));

        BookOutcome outcome = slotBookService.book(slot.getId(), reservationId);

        assertThat(outcome).isEqualTo(BookOutcome.HOLD_EXPIRED);
        assertThat(slotRepository.findById(slot.getId()).orElseThrow().getStatus())
                .isEqualTo(SlotStatus.AVAILABLE);
    }

    @Test
    void 다른_예약이_HELD한_슬롯이면_FAILED이고_상대_HOLD를_유지한다() {
        UUID otherReservationId = UUID.randomUUID();
        Slot slot = slotRepository.saveAndFlush(SlotFixture.held(otherReservationId, NOW.plusMinutes(3)));

        BookOutcome outcome = slotBookService.book(slot.getId(), UUID.randomUUID());

        assertThat(outcome).isEqualTo(BookOutcome.FAILED);
        Slot saved = slotRepository.findById(slot.getId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(SlotStatus.HELD);
        assertThat(saved.getReservationId()).isEqualTo(otherReservationId);
        assertThat(outboxEventRepository.findAll()).isEmpty();
    }

    @Test
    void 다른_예약의_HOLD가_만료됐어도_만료_처리하지_않고_FAILED를_반환한다() {
        UUID otherReservationId = UUID.randomUUID();
        Slot slot = slotRepository.saveAndFlush(SlotFixture.held(otherReservationId, NOW.minusMinutes(1)));

        BookOutcome outcome = slotBookService.book(slot.getId(), UUID.randomUUID());

        assertThat(outcome).isEqualTo(BookOutcome.FAILED);
        assertThat(slotRepository.findById(slot.getId()).orElseThrow().getStatus()).isEqualTo(SlotStatus.HELD);
        assertThat(outboxEventRepository.findAll()).isEmpty();   // 만료 처리는 스케줄러 몫
    }

    @Test
    void AVAILABLE_슬롯이면_FAILED를_반환한다() {
        Slot slot = slotRepository.saveAndFlush(SlotFixture.available());

        BookOutcome outcome = slotBookService.book(slot.getId(), UUID.randomUUID());

        assertThat(outcome).isEqualTo(BookOutcome.FAILED);
        assertThat(slotRepository.findById(slot.getId()).orElseThrow().getStatus()).isEqualTo(SlotStatus.AVAILABLE);
    }

    @Test
    void UNAVAILABLE_슬롯이면_FAILED를_반환한다() {
        Slot slot = slotRepository.saveAndFlush(SlotFixture.unavailable());

        BookOutcome outcome = slotBookService.book(slot.getId(), UUID.randomUUID());

        assertThat(outcome).isEqualTo(BookOutcome.FAILED);
        assertThat(slotRepository.findById(slot.getId()).orElseThrow().getStatus()).isEqualTo(SlotStatus.UNAVAILABLE);
    }

    @Test
    void 삭제된_슬롯이면_같은_예약으로_HELD여도_FAILED를_반환한다() {
        UUID reservationId = UUID.randomUUID();
        Slot slot = SlotFixture.held(reservationId, NOW.plusMinutes(3));
        ReflectionTestUtils.setField(slot, "deletedAt", NOW.minusMinutes(1));
        slotRepository.saveAndFlush(slot);

        BookOutcome outcome = slotBookService.book(slot.getId(), reservationId);

        assertThat(outcome).isEqualTo(BookOutcome.FAILED);
        assertThat(slotRepository.findById(slot.getId()).orElseThrow().getStatus()).isEqualTo(SlotStatus.HELD);
        assertThat(outboxEventRepository.findAll()).isEmpty();
    }

    @Test
    void 없는_슬롯이면_예외_없이_FAILED를_반환한다() {
        BookOutcome outcome = slotBookService.book(UUID.randomUUID(), UUID.randomUUID());

        assertThat(outcome).isEqualTo(BookOutcome.FAILED);
    }
}
