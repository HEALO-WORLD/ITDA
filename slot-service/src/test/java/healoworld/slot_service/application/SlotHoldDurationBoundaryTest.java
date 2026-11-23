package healoworld.slot_service.application;

import healoworld.slot_service.support.PostgresContainerSupport;

import healoworld.slot_service.application.dto.BookOutcome;
import healoworld.slot_service.application.dto.HoldResult;
import healoworld.slot_service.application.scheduler.HoldExpiryScheduler;
import healoworld.slot_service.application.service.SlotBookService;
import healoworld.slot_service.application.service.SlotHoldExpiryService;
import healoworld.slot_service.application.service.SlotHoldService;
import healoworld.slot_service.domain.entity.Slot;
import healoworld.slot_service.domain.entity.SlotStatusHistory;
import healoworld.slot_service.domain.enums.SlotStatus;
import healoworld.slot_service.domain.enums.SlotStatusChangeReason;
import healoworld.slot_service.global.config.ClockConfig;
import healoworld.slot_service.global.outbox.OutboxEvent;
import healoworld.slot_service.global.outbox.OutboxEventRepository;
import healoworld.slot_service.infrastructure.repository.ExpiredHold;
import healoworld.slot_service.infrastructure.repository.SlotRepository;
import healoworld.slot_service.infrastructure.repository.SlotStatusHistoryRepository;
import healoworld.slot_service.support.fixture.SlotFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// HOLD 유지 시간(Slot.HOLD_DURATION = 5분)이 실제 만료·확정 판단에 그대로 적용되는지 경계값으로 검증한다.
// 기존 테스트는 heldUntil을 직접 넣어 만료 조건(held_until <= now)만 확인하므로,
// 여기서는 실제 hold()로 heldUntil을 만든 뒤 시계를 HOLD 시작 시각에서 4분 59초 / 5분 / 5분 1초 앞으로 옮겨 확인한다.
// 만료는 스케줄러 경로(findExpiredHolds → expireHoldIfDue)와 BookSlot 경로(bookIfHeld) 둘 다 실제 쿼리로 검증한다
@SpringBootTest
@ActiveProfiles("test")
class SlotHoldDurationBoundaryTest extends PostgresContainerSupport {

    // FixedClockConfig.NOW와 같은 시각. 슬롯(SlotFixture 기본 2026-11-02 09:00)보다 앞이라 HOLD할 수 있다
    private static final LocalDateTime HOLD_STARTED_AT = LocalDateTime.of(2026, 10, 30, 10, 0);
    private static final LocalDateTime EXPECTED_HELD_UNTIL = HOLD_STARTED_AT.plusMinutes(5);

    @Autowired
    private SlotHoldService slotHoldService;

    @Autowired
    private SlotBookService slotBookService;

    @Autowired
    private SlotHoldExpiryService slotHoldExpiryService;

    @Autowired
    private SlotRepository slotRepository;

    @Autowired
    private SlotStatusHistoryRepository slotStatusHistoryRepository;

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private MutableClock clock;

    // 테스트 프로필은 스케줄러 빈을 끄므로(hold-expiry.enabled=false) 직접 만든다 (HoldExpirySchedulerTest와 같은 방식)
    private HoldExpiryScheduler scheduler;

    @BeforeEach
    void setUp() {
        clock.setTo(HOLD_STARTED_AT);
        scheduler = new HoldExpiryScheduler(slotRepository, slotHoldExpiryService, clock);
    }

    @AfterEach
    void tearDown() {
        outboxEventRepository.deleteAllInBatch();
        slotStatusHistoryRepository.deleteAllInBatch();
        slotRepository.deleteAllInBatch();
    }

    @Test
    void HOLD_만료시각은_HOLD_시작_시각에서_정확히_5분_뒤다() {
        assertThat(Slot.HOLD_DURATION).isEqualTo(Duration.ofMinutes(5));

        UUID reservationId = UUID.randomUUID();
        Slot slot = holdAt(reservationId);

        assertThat(slotRepository.findById(slot.getId()).orElseThrow().getHeldUntil())
                .isEqualTo(EXPECTED_HELD_UNTIL);
    }

    // ---------- 스케줄러 만료 경로 (findExpiredHolds → expireHoldIfDue) ----------

    @Test
    void HOLD_후_4분59초에는_만료되지_않고_HELD를_유지한다() {
        UUID reservationId = UUID.randomUUID();
        Slot slot = holdAt(reservationId);

        clock.setTo(HOLD_STARTED_AT.plusMinutes(4).plusSeconds(59));

        assertThat(findExpiredHolds()).isEmpty();
        scheduler.expireHolds();

        Slot saved = slotRepository.findById(slot.getId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(SlotStatus.HELD);
        assertThat(saved.getReservationId()).isEqualTo(reservationId);
        assertThat(saved.getHeldUntil()).isEqualTo(EXPECTED_HELD_UNTIL);
        assertThat(outboxEventRepository.findAll()).isEmpty();
        assertThat(historiesOf(SlotStatusChangeReason.HOLD_EXPIRED)).isEmpty();
    }

    @Test
    void HOLD_후_정확히_5분이면_만료한다() {
        assertExpiredByScheduler(HOLD_STARTED_AT.plusMinutes(5));
    }

    @Test
    void HOLD_후_5분1초면_만료한다() {
        assertExpiredByScheduler(HOLD_STARTED_AT.plusMinutes(5).plusSeconds(1));
    }

    // ---------- BookSlot 경로 (bookIfHeld: held_until > now) ----------

    @Test
    void HOLD_후_4분59초에_도착한_BookSlot은_확정한다() {
        UUID reservationId = UUID.randomUUID();
        Slot slot = holdAt(reservationId);

        clock.setTo(HOLD_STARTED_AT.plusMinutes(4).plusSeconds(59));
        BookOutcome outcome = slotBookService.book(slot.getId(), reservationId, UUID.randomUUID());

        assertThat(outcome).isEqualTo(BookOutcome.BOOKED);
        Slot saved = slotRepository.findById(slot.getId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(SlotStatus.BOOKED);
        assertThat(saved.getReservationId()).isEqualTo(reservationId);
        assertThat(saved.getHeldUntil()).isNull();
    }

    @Test
    void HOLD_후_정확히_5분에_도착한_BookSlot은_확정하지_않고_만료_처리한다() {
        UUID reservationId = UUID.randomUUID();
        Slot slot = holdAt(reservationId);

        LocalDateTime now = HOLD_STARTED_AT.plusMinutes(5);
        clock.setTo(now);
        BookOutcome outcome = slotBookService.book(slot.getId(), reservationId, UUID.randomUUID());

        assertThat(outcome).isEqualTo(BookOutcome.HOLD_EXPIRED);
        assertReleasedAsExpired(slot, reservationId, now);
    }

    // ---------- helpers ----------

    // HOLD_STARTED_AT에 실제 hold()로 슬롯을 잡는다. heldUntil은 서비스가 HOLD_DURATION으로 계산한다
    private Slot holdAt(UUID reservationId) {
        Slot slot = slotRepository.saveAndFlush(SlotFixture.available());
        clock.setTo(HOLD_STARTED_AT);

        HoldResult result = slotHoldService.hold(slot.getId(), reservationId, UUID.randomUUID());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getHeldUntil()).isEqualTo(EXPECTED_HELD_UNTIL);
        return slot;
    }

    private void assertExpiredByScheduler(LocalDateTime now) {
        UUID reservationId = UUID.randomUUID();
        Slot slot = holdAt(reservationId);

        clock.setTo(now);

        List<ExpiredHold> targets = findExpiredHolds();
        assertThat(targets).extracting(ExpiredHold::slotId).containsExactly(slot.getId());
        assertThat(targets.getFirst().reservationId()).isEqualTo(reservationId);
        assertThat(targets.getFirst().heldUntil()).isEqualTo(EXPECTED_HELD_UNTIL);

        scheduler.expireHolds();

        assertReleasedAsExpired(slot, reservationId, now);
    }

    // 기존 만료 정책: 장비 중단이 없는 HOLD는 AVAILABLE로 돌아가고 예약 연결·만료시각을 지운다.
    // HOLD_EXPIRED 시스템 이력과 HoldExpired 결과 이벤트(heldUntil = 원래 만료시각)를 남긴다
    private void assertReleasedAsExpired(Slot slot, UUID reservationId, LocalDateTime now) {
        Slot saved = slotRepository.findById(slot.getId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(SlotStatus.AVAILABLE);
        assertThat(saved.getReservationId()).isNull();
        assertThat(saved.getHeldUntil()).isNull();
        assertThat(saved.getUpdatedAt()).isEqualTo(now);

        List<SlotStatusHistory> expiredHistories = historiesOf(SlotStatusChangeReason.HOLD_EXPIRED);
        assertThat(expiredHistories).hasSize(1);
        SlotStatusHistory history = expiredHistories.getFirst();
        assertThat(history.getReservationId()).isEqualTo(reservationId);
        assertThat(history.getFromStatus()).isEqualTo(SlotStatus.HELD);
        assertThat(history.getToStatus()).isEqualTo(SlotStatus.AVAILABLE);
        assertThat(history.getChangedAt()).isEqualTo(now);

        List<OutboxEvent> events = outboxEventRepository.findAll();
        assertThat(events).hasSize(1);
        OutboxEvent outbox = events.getFirst();
        assertThat(outbox.getEventType()).isEqualTo("HoldExpired");
        assertThat(outbox.getMessageKey()).isEqualTo(slot.getId().toString());

        JsonNode payload = jsonMapper.readTree(outbox.getPayload());
        assertThat(payload.get("reservationId").asText()).isEqualTo(reservationId.toString());
        assertThat(payload.get("heldUntil").asText()).isEqualTo("2026-10-30T10:05:00");
    }

    private List<ExpiredHold> findExpiredHolds() {
        return slotRepository.findExpiredHolds(SlotStatus.HELD, LocalDateTime.now(clock), PageRequest.of(0, 100));
    }

    private List<SlotStatusHistory> historiesOf(SlotStatusChangeReason reason) {
        return slotStatusHistoryRepository.findAll().stream()
                .filter(history -> history.getReason() == reason)
                .toList();
    }

    // 테스트 안에서 시각을 옮길 수 있는 Clock. 서비스·스케줄러가 주입받는 Clock을 이것으로 바꾼다
    static class MutableClock extends Clock {

        private final ZoneId zone;
        private Instant instant;

        MutableClock(LocalDateTime now, ZoneId zone) {
            this.zone = zone;
            this.instant = now.atZone(zone).toInstant();
        }

        void setTo(LocalDateTime now) {
            this.instant = now.atZone(zone).toInstant();
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(instant, zone);
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    @TestConfiguration
    static class MutableClockConfig {

        @Bean
        @Primary
        MutableClock mutableClock() {
            // 운영 Clock과 같은 한국 시간 기준
            return new MutableClock(HOLD_STARTED_AT, ClockConfig.ZONE);
        }
    }
}
