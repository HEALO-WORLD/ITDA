package healoworld.referral_service.application.scheduler;

import healoworld.referral_service.application.event.command.SlotCommandType;
import healoworld.referral_service.application.event.result.SlotResultType;
import healoworld.referral_service.application.service.EquipmentIncidentService;
import healoworld.referral_service.application.service.ExaminationService;
import healoworld.referral_service.application.service.IncidentProcessingService;
import healoworld.referral_service.application.service.NotificationService;
import healoworld.referral_service.application.service.ReferralHistoryService;
import healoworld.referral_service.application.service.ReservationReconciliationService;
import healoworld.referral_service.application.service.ReservationService;
import healoworld.referral_service.application.service.SlotResultService;
import healoworld.referral_service.domain.entity.Referral;
import healoworld.referral_service.domain.entity.ReferralHistory;
import healoworld.referral_service.domain.entity.Reservation;
import healoworld.referral_service.domain.enums.FailureReason;
import healoworld.referral_service.domain.enums.Reason;
import healoworld.referral_service.domain.enums.ReferralStatus;
import healoworld.referral_service.domain.enums.ReservationStatus;
import healoworld.referral_service.global.config.ClockConfig;
import healoworld.referral_service.global.config.QuerydslConfig;
import healoworld.referral_service.global.outbox.OutboxEvent;
import healoworld.referral_service.global.outbox.OutboxEventRepository;
import healoworld.referral_service.global.outbox.SlotCommandOutbox;
import healoworld.referral_service.infrastructure.client.slot.SlotClient;
import healoworld.referral_service.infrastructure.client.slot.SlotStatusResponse;
import healoworld.referral_service.infrastructure.repository.EquipmentIncidentLockRepository;
import healoworld.referral_service.infrastructure.repository.ExaminationRepository;
import healoworld.referral_service.infrastructure.repository.ReferralHistoryRepository;
import healoworld.referral_service.infrastructure.repository.ReferralRepository;
import healoworld.referral_service.infrastructure.repository.ReservationRepository;
import healoworld.referral_service.support.PostgresContainerSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
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
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 정합성 점검 스케줄러가 실제 DB의 예약을 찾아, 복구 이벤트를 실제 SlotResultService로 처리해 상태를 바꾸는지 검증한다.
 * <p>
 * - slot-service 응답(SlotClient)만 mock으로 두고, 대상 조회·판단·상태 전이·outbox·이력은 모두 실제로 실행한다
 * - 서비스가 실제로 커밋하므로 테스트 트랜잭션을 끄고, 만든 행만 끝날 때 지운다
 */
@DataJpaTest
@Import({
        ReservationReconciliationService.class,
        SlotResultService.class,
        EquipmentIncidentService.class,
        EquipmentIncidentLockRepository.class,
        IncidentProcessingService.class,
        NotificationService.class,
        ExaminationService.class,
        ReservationService.class,
        ReferralHistoryService.class,
        SlotCommandOutbox.class,
        QuerydslConfig.class, ClockConfig.class,
        ReservationReconciliationIntegrationTest.JsonConfig.class
})
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
// 이 조합의 컨텍스트는 다른 테스트와 공유되지 않으므로, 끝나면 닫아 DB 연결을 돌려준다 (Postgres 연결 수 한도)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ReservationReconciliationIntegrationTest extends PostgresContainerSupport {

    @TestConfiguration(proxyBeanMethods = false)
    static class JsonConfig {
        @Bean
        JsonMapper jsonMapper() {
            return JsonMapper.builder().build();
        }
    }

    // 공유 DB에 다른 테스트가 남긴 예약이 있어도 이번 대상이 한 회차에 다 들어오도록 넉넉하게 잡는다
    private static final int BATCH_SIZE = 10_000;
    private static final int STALE_THRESHOLD_MINUTES = 10;
    private static final int LOOKBACK_HOURS = 24;

    @Autowired private ReservationReconciliationService reconciliationService;
    @Autowired private SlotResultService slotResultService;
    @Autowired private ReferralRepository referralRepository;
    @Autowired private ReservationRepository reservationRepository;
    @Autowired private ReferralHistoryRepository referralHistoryRepository;
    @Autowired private OutboxEventRepository outboxEventRepository;
    @Autowired private ExaminationRepository examinationRepository;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbcTemplate;

    private final SlotClient slotClient = mock(SlotClient.class);
    private final List<SlotStatusResponse> slotResponses = new ArrayList<>();
    private final List<UUID> referralIds = new ArrayList<>();
    private final List<UUID> reservationIds = new ArrayList<>();
    private ReservationReconciliationScheduler scheduler;
    private LocalDateTime startedAt;

    private record Booking(UUID referralId, UUID reservationId, UUID slotId) {
    }

    @BeforeEach
    void setUp() {
        startedAt = LocalDateTime.now();
        scheduler = new ReservationReconciliationScheduler(reconciliationService, reservationRepository, slotClient,
                BATCH_SIZE, LOOKBACK_HOURS, STALE_THRESHOLD_MINUTES);
        // slot-service는 테스트에서 등록한 슬롯만 알려준다. 다른 테스트가 남긴 예약은 "슬롯 없음"으로 건너뛴다
        when(slotClient.getSlotStatuses(any())).thenAnswer(invocation -> List.copyOf(slotResponses));
    }

    @AfterEach
    void cleanUp() {
        referralIds.forEach(id -> referralHistoryRepository.deleteAll(
                referralHistoryRepository.findAllByReferralIdOrderByOccurredAtAsc(id)));
        outboxEventRepository.deleteAll(outboxEventRepository.findAll().stream()
                .filter(e -> reservationIds.contains(e.getAggregateId())).toList());
        // 복구 이벤트의 eventId는 서비스 안에서 만들어지므로, 이 테스트가 시작된 뒤 기록된 소비 이력을 지운다
        jdbcTemplate.update("delete from p_consumed_events where processed_at >= ?", startedAt);
        referralIds.forEach(id -> examinationRepository.findByReferralId(id).ifPresent(examinationRepository::delete));
        reservationRepository.deleteAllById(reservationIds);
        referralRepository.deleteAllById(referralIds);
    }

    // ---------- 진행 중 예약 복구 ----------

    @Test
    @DisplayName("SlotHeld 유실: 멈춘 REQUESTED + 슬롯이 이 예약으로 HELD → 예약 HELD, BookSlot 발행")
    void recoversLostSlotHeld() {
        Booking booking = booking(reservation -> {
        }, referral -> {
        });
        markStale(booking);
        slot(booking, "HELD", booking.reservationId(), LocalDateTime.now().plusMinutes(3));

        scheduler.reconcileReservations();

        assertThat(reservationStatus(booking)).isEqualTo(ReservationStatus.HELD);
        assertThat(outboxTypes(booking)).containsExactly(SlotCommandType.BOOK_SLOT.value());
    }

    @Test
    @DisplayName("SlotBooked 유실: 멈춘 HELD + 슬롯이 이 예약으로 BOOKED → 예약·의뢰 CONFIRMED, 이력·검사 생성")
    void recoversLostSlotBooked() {
        Booking booking = booking(Reservation::hold, referral -> {
        });
        markStale(booking);
        slot(booking, "BOOKED", booking.reservationId(), null);

        scheduler.reconcileReservations();

        Reservation reservation = reservationRepository.findById(booking.reservationId()).orElseThrow();
        assertThat(reservation.getReservationStatus()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(referralStatus(booking)).isEqualTo(ReferralStatus.CONFIRMED);
        assertThat(histories(booking)).singleElement()
                .satisfies(history -> assertThat(history.getReason()).isEqualTo(Reason.RESERVATION_CONFIRMED));
        assertThat(examinationRepository.findByReferralId(booking.referralId())).isPresent();
    }

    @Test
    @DisplayName("HoldExpired 유실: 멈춘 HELD + 슬롯이 풀림 → 예약 FAILED(HOLD_EXPIRED), 첫 예약이라 의뢰 FAILED, ReleaseHold 발행")
    void recoversLostHoldExpired() {
        Booking booking = booking(Reservation::hold, referral -> {
        });
        markStale(booking);
        // 해제 후에도 예전 reservationId가 남아 있는 경우까지 "소유 아님"으로 판단해야 한다
        slot(booking, "AVAILABLE", booking.reservationId(), null);

        scheduler.reconcileReservations();

        Reservation reservation = reservationRepository.findById(booking.reservationId()).orElseThrow();
        assertThat(reservation.getReservationStatus()).isEqualTo(ReservationStatus.FAILED);
        assertThat(reservation.getFailureReason()).isEqualTo(FailureReason.HOLD_EXPIRED);
        assertThat(referralStatus(booking)).isEqualTo(ReferralStatus.FAILED);
        assertThat(outboxTypes(booking)).containsExactly(SlotCommandType.RELEASE_HOLD.value());
    }

    @Test
    @DisplayName("아직 기준 시간이 지나지 않은 진행 중 예약은 슬롯과 달라도 건드리지 않는다")
    void ignoresRecentInProgress() {
        Booking booking = booking(Reservation::hold, referral -> {
        });
        slot(booking, "AVAILABLE", null, null);   // 오래됐다면 HOLD_EXPIRED 대상

        scheduler.reconcileReservations();

        assertThat(reservationStatus(booking)).isEqualTo(ReservationStatus.HELD);
        assertThat(outboxTypes(booking)).isEmpty();
    }

    // ---------- 확정·종료 예약 복구 ----------

    @Test
    @DisplayName("SlotsBlocked 유실: CONFIRMED + 슬롯 UNAVAILABLE → 예약 BLOCKED, 의뢰 NEEDS_RESCHEDULE")
    void recoversLostSlotsBlocked() {
        Booking booking = booking(reservation -> {
            reservation.hold();
            reservation.confirm();
        }, Referral::confirm);
        slot(booking, "UNAVAILABLE", booking.reservationId(), null);

        scheduler.reconcileReservations();

        assertThat(reservationStatus(booking)).isEqualTo(ReservationStatus.BLOCKED);
        assertThat(referralStatus(booking)).isEqualTo(ReferralStatus.NEEDS_RESCHEDULE);
        assertThat(histories(booking)).singleElement()
                .satisfies(history -> assertThat(history.getReason()).isEqualTo(Reason.RESERVATION_BLOCKED));
    }

    @Test
    @DisplayName("ReleaseSlot 유실: 취소된 예약 + 슬롯이 여전히 이 예약으로 BOOKED → 상태는 그대로, ReleaseSlot 발행")
    void releasesOrphanSlot() {
        Booking booking = booking(reservation -> {
            reservation.hold();
            reservation.confirm();
            reservation.cancel();
        }, referral -> {
            referral.confirm();
            referral.cancel();
        });
        slot(booking, "BOOKED", booking.reservationId(), null);

        scheduler.reconcileReservations();

        assertThat(reservationStatus(booking)).isEqualTo(ReservationStatus.CANCELED);
        assertThat(outboxTypes(booking)).containsExactly(SlotCommandType.RELEASE_SLOT.value());
    }

    @Test
    @DisplayName("정상 확정 예약(슬롯이 이 예약으로 BOOKED)은 아무것도 바꾸지 않는다")
    void ignoresHealthyConfirmed() {
        Booking booking = booking(reservation -> {
            reservation.hold();
            reservation.confirm();
        }, Referral::confirm);
        slot(booking, "BOOKED", booking.reservationId(), null);

        scheduler.reconcileReservations();

        assertThat(reservationStatus(booking)).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(referralStatus(booking)).isEqualTo(ReferralStatus.CONFIRMED);
        assertThat(histories(booking)).isEmpty();
        assertThat(outboxTypes(booking)).isEmpty();
    }

    // ---------- 중복 실행·늦은 이벤트 ----------

    @Test
    @DisplayName("복구 후 회차를 다시 돌려도, 진짜 SlotBooked가 늦게 도착해도 이력·검사는 한 번만 생긴다")
    void idempotentAfterRecovery() {
        Booking booking = booking(Reservation::hold, referral -> {
        });
        markStale(booking);
        slot(booking, "BOOKED", booking.reservationId(), null);

        scheduler.reconcileReservations();
        scheduler.reconcileReservations();
        slotResultService.handle(UUID.randomUUID(), SlotResultType.SLOT_BOOKED, booking.reservationId());   // 유실된 줄 알았던 진짜 이벤트

        assertThat(reservationStatus(booking)).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(histories(booking)).hasSize(1);
        assertThat(outboxTypes(booking)).isEmpty();
    }

    // ---------- 픽스처 ----------

    // 예약은 슬롯 시간이 3일 뒤로, 상태 전이는 엔티티 메서드로 만든다
    private Booking booking(Consumer<Reservation> reservationState, Consumer<Referral> referralState) {
        LocalDateTime startsAt = LocalDateTime.now().plusDays(3).withHour(14).withMinute(0).withSecond(0).withNano(0);
        Booking booking = tx().execute(status -> {
            Referral referral = referralRepository.save(Referral.create(UUID.randomUUID(), UUID.randomUUID(),
                    UUID.randomUUID(), "가상환자", UUID.randomUUID()));
            Reservation reservation = Reservation.create(referral, 1, UUID.randomUUID(), UUID.randomUUID(),
                    UUID.randomUUID(), startsAt, startsAt, startsAt.plusHours(1));
            reservationRepository.save(reservation);
            referral.assignReservation(reservation.getId());
            reservationState.accept(reservation);
            referralState.accept(referral);
            return new Booking(referral.getId(), reservation.getId(), reservation.getSlotId());
        });
        referralIds.add(booking.referralId());
        reservationIds.add(booking.reservationId());
        return booking;
    }

    // 진행 중 예약이 기준 시간보다 오래 멈춘 상태로 만든다
    private void markStale(Booking booking) {
        jdbcTemplate.update("update p_reservations set updated_at = ? where id = ?",
                LocalDateTime.now().minusMinutes(STALE_THRESHOLD_MINUTES * 3L), booking.reservationId());
    }

    private void slot(Booking booking, String status, UUID reservationId, LocalDateTime heldUntil) {
        slotResponses.add(new SlotStatusResponse(booking.slotId(), status, reservationId, heldUntil));
    }

    private ReservationStatus reservationStatus(Booking booking) {
        return reservationRepository.findById(booking.reservationId()).orElseThrow().getReservationStatus();
    }

    private ReferralStatus referralStatus(Booking booking) {
        return referralRepository.findById(booking.referralId()).orElseThrow().getReferralStatus();
    }

    private List<ReferralHistory> histories(Booking booking) {
        return referralHistoryRepository.findAllByReferralIdOrderByOccurredAtAsc(booking.referralId());
    }

    private List<String> outboxTypes(Booking booking) {
        return outboxEventRepository.findAll().stream()
                .filter(e -> e.getAggregateId().equals(booking.reservationId()))
                .map(OutboxEvent::getEventType)
                .toList();
    }

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }
}
