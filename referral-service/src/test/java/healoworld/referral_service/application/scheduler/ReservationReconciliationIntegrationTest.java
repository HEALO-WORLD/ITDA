package healoworld.referral_service.application.scheduler;

import healoworld.referral_service.domain.entity.ReconciliationFailure;
import healoworld.referral_service.domain.enums.ReconciliationFailureStatus;
import healoworld.referral_service.global.exception.BaseException;
import healoworld.referral_service.global.exception.ErrorCode;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.dao.QueryTimeoutException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import healoworld.referral_service.application.event.command.SlotCommandType;
import healoworld.referral_service.application.event.result.SlotResultType;
import healoworld.referral_service.application.service.EquipmentIncidentService;
import healoworld.referral_service.application.service.ExaminationService;
import healoworld.referral_service.application.service.IncidentProcessingService;
import healoworld.referral_service.application.service.InstitutionNotificationService;
import healoworld.referral_service.application.service.NotificationService;
import healoworld.referral_service.application.service.ReferralHistoryService;
import healoworld.referral_service.application.service.ReconciliationFailureRecorder;
import healoworld.referral_service.application.service.ReservationReconciliationService;
import healoworld.referral_service.infrastructure.repository.ReconciliationFailureRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import healoworld.referral_service.application.service.ReservationService;
import healoworld.referral_service.application.service.SlotResultService;
import healoworld.referral_service.domain.entity.Examination;
import healoworld.referral_service.domain.entity.Referral;
import healoworld.referral_service.domain.entity.ReferralHistory;
import healoworld.referral_service.domain.entity.Reservation;
import healoworld.referral_service.domain.enums.ExaminationStatus;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
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
        ReconciliationFailureRecorder.class,
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

        // 복구 결과 메트릭 확인용 (DataJpaTest에는 MeterRegistry가 없다)
        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }

    // 공유 DB에 다른 테스트가 남긴 예약이 있어도 이번 대상이 한 회차에 다 들어오도록 넉넉하게 잡는다
    // (스케줄러가 허용하는 최대값: 쿼리 3종 × batchSize ≤ slot-service 조회 한도)
    private static final int BATCH_SIZE = ReservationReconciliationScheduler.SLOT_STATUS_QUERY_MAX_IDS / 3;
    private static final int STALE_THRESHOLD_MINUTES = 10;
    private static final int LOOKBACK_HOURS = 24;

    @Autowired private ReservationReconciliationService reconciliationService;
    @Autowired private ReconciliationFailureRecorder failureRecorder;
    @Autowired private ReconciliationFailureRepository failureRepository;
    @Autowired private MeterRegistry meterRegistry;
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
                examinationRepository, failureRecorder, meterRegistry, BATCH_SIZE, LOOKBACK_HOURS, STALE_THRESHOLD_MINUTES);
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
        referralIds.forEach(id -> jdbcTemplate.update("delete from p_institution_notifications where referral_id = ?", id));
        reservationIds.forEach(id -> failureRepository.findByReservationId(id).ifPresent(failureRepository::delete));
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

    // ---------- SlotsBlocked 사전 확인: 검사가 시작·완료된 확정 예약 ----------

    @ParameterizedTest(name = "검사 {0}")
    @EnumSource(value = ExaminationStatus.class, names = {"IN_PROGRESS", "COMPLETED"})
    @DisplayName("F. 검사가 시작·완료된 확정 예약은 회차를 2번 돌려도 상태·이력이 그대로이고 inbox도 남지 않는다")
    void skipsStartedExaminationAcrossRounds(ExaminationStatus examinationStatus) {
        Booking booking = confirmedBooking();
        examination(booking, booking.reservationId(), examinationStatus);
        slot(booking, "UNAVAILABLE", null, null);

        scheduler.reconcileReservations();
        scheduler.reconcileReservations();

        assertThat(reservationStatus(booking)).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(referralStatus(booking)).isEqualTo(ReferralStatus.CONFIRMED);
        assertThat(histories(booking)).isEmpty();
        assertThat(consumedEventsSinceStart()).isZero();
    }

    @Test
    @DisplayName("C. 검사가 SCHEDULED인 확정 예약은 기존처럼 SlotsBlocked로 차단한다")
    void blocksConfirmedWithScheduledExamination() {
        Booking booking = confirmedBooking();
        examination(booking, booking.reservationId(), ExaminationStatus.SCHEDULED);
        slot(booking, "UNAVAILABLE", null, null);

        scheduler.reconcileReservations();

        assertThat(reservationStatus(booking)).isEqualTo(ReservationStatus.BLOCKED);
        assertThat(referralStatus(booking)).isEqualTo(ReferralStatus.NEEDS_RESCHEDULE);
        assertThat(histories(booking)).singleElement()
                .satisfies(history -> assertThat(history.getReason()).isEqualTo(Reason.RESERVATION_BLOCKED));
    }

    @Test
    @DisplayName("G. 검사가 다른 예약에 연결돼 있어도 같은 의뢰의 검사가 IN_PROGRESS면 주입하지 않는다 (의뢰 기준 판단)")
    void protectsByReferralEvenIfExaminationLinkedElsewhere() {
        Booking booking = confirmedBooking();
        examination(booking, UUID.randomUUID(), ExaminationStatus.IN_PROGRESS);   // 검사의 reservationId는 이 예약이 아님
        slot(booking, "UNAVAILABLE", null, null);

        scheduler.reconcileReservations();

        assertThat(reservationStatus(booking)).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(referralStatus(booking)).isEqualTo(ReferralStatus.CONFIRMED);
        assertThat(histories(booking)).isEmpty();
        assertThat(consumedEventsSinceStart()).isZero();
    }

    @Test
    @DisplayName("H. 사전 확인 뒤 검사가 시작돼 주입되더라도 SlotsBlocked 최종 확인(잠금)이 차단을 막는다")
    void finalLockCheckStillGuardsAfterPreCheck() {
        // 사전 확인 시점에는 검사가 시작 전이었던 상황: 사전 조회가 아무것도 돌려주지 않는 Repository로 스케줄러를 만든다
        ReservationReconciliationScheduler staleView = new ReservationReconciliationScheduler(reconciliationService,
                reservationRepository, slotClient, mock(ExaminationRepository.class), failureRecorder, meterRegistry,
                BATCH_SIZE, LOOKBACK_HOURS, STALE_THRESHOLD_MINUTES);
        Booking booking = confirmedBooking();
        examination(booking, booking.reservationId(), ExaminationStatus.IN_PROGRESS);
        slot(booking, "UNAVAILABLE", null, null);

        staleView.reconcileReservations();

        assertThat(consumedEventsSinceStart()).isEqualTo(1);   // 주입은 됐고
        assertThat(reservationStatus(booking)).isEqualTo(ReservationStatus.CONFIRMED);   // 최종 확인이 건너뛰었다
        assertThat(referralStatus(booking)).isEqualTo(ReferralStatus.CONFIRMED);
        assertThat(histories(booking)).isEmpty();
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

    // ---------- 장비 장애로 확정 뒤 차단된 예약: 정책상 BOOKED 슬롯·예약 연결 유지 ----------

    @Test
    @DisplayName("확정 뒤 장비 장애로 차단된 예약의 BOOKED 슬롯은 회차를 2번 돌려도 ReleaseSlot·inbox·상태 변경이 없다")
    void keepsBookedSlotOfEquipmentBlockedAfterConfirm() {
        Booking booking = booking(reservation -> {
            reservation.hold();
            reservation.confirm();
            reservation.block();   // 장애 후속 처리와 같은 전이 (EQUIPMENT_FAILURE, 확정 시각 유지)
        }, referral -> {
            referral.confirm();
            referral.requestReschedule();
        });
        slot(booking, "BOOKED", booking.reservationId(), null);   // slot-service는 장애 중에도 BOOKED·예약 연결을 유지한다

        scheduler.reconcileReservations();
        scheduler.reconcileReservations();

        assertThat(outboxTypes(booking)).isEmpty();
        assertThat(consumedEventsSinceStart()).isZero();
        assertThat(reservationStatus(booking)).isEqualTo(ReservationStatus.BLOCKED);
        assertThat(referralStatus(booking)).isEqualTo(ReferralStatus.NEEDS_RESCHEDULE);
        assertThat(histories(booking)).isEmpty();
    }

    @Test
    @DisplayName("확정 전(HELD)에 장비 장애로 차단된 예약에 슬롯이 BOOKED로 남아 있으면 늦은 확정으로 보고 기존처럼 ReleaseSlot을 발행한다")
    void releasesSlotOfEquipmentBlockedBeforeConfirm() {
        Booking booking = booking(reservation -> {
            reservation.hold();
            reservation.block();   // 확정 시각 없음
        }, Referral::requestReschedule);
        slot(booking, "BOOKED", booking.reservationId(), null);

        scheduler.reconcileReservations();

        assertThat(reservationStatus(booking)).isEqualTo(ReservationStatus.BLOCKED);
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

    // ---------- SlotBooked 복구 가드·실패 기록 (E2E S1·S2a·S2b) ----------

    @Test
    @DisplayName("S1. 멈춘 HELD + 슬롯 BOOKED + 다른 예약에 연결된 SCHEDULED 검사 → 예약·의뢰 CONFIRMED, 검사는 현재 예약으로 재연결, 실패 기록 없음")
    void s1RecoversHeldWithScheduledExamination() {
        Booking booking = booking(Reservation::hold, referral -> {
        });
        examination(booking, UUID.randomUUID(), ExaminationStatus.SCHEDULED);   // 이전 예약에 연결된 검사 (재예약 상황)
        markStale(booking);
        slot(booking, "BOOKED", booking.reservationId(), null);
        double recoveredBefore = metric("recovered");

        scheduler.reconcileReservations();

        assertThat(reservationStatus(booking)).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(referralStatus(booking)).isEqualTo(ReferralStatus.CONFIRMED);
        assertThat(examinationRepository.findByReferralId(booking.referralId())).get()
                .satisfies(e -> {
                    assertThat(e.getStatus()).isEqualTo(ExaminationStatus.SCHEDULED);
                    assertThat(e.getReservationId()).isEqualTo(booking.reservationId());
                });
        assertThat(histories(booking)).singleElement()
                .satisfies(history -> assertThat(history.getReason()).isEqualTo(Reason.RESERVATION_CONFIRMED));
        assertThat(outboxTypes(booking)).isEmpty();   // 슬롯 해제 명령 없음 (BOOKED 유지)
        assertThat(failureRepository.findByReservationId(booking.reservationId())).isEmpty();
        assertThat(metric("recovered") - recoveredBefore).isEqualTo(1);
    }

    @ParameterizedTest(name = "검사 {0}, 검사 연결 = {1}")
    @CsvSource({"COMPLETED, CURRENT", "COMPLETED, PREVIOUS", "IN_PROGRESS, CURRENT", "IN_PROGRESS, PREVIOUS"})
    @DisplayName("S2a·S2b. 멈춘 HELD + 슬롯 BOOKED + 시작·완료된 검사 → 사전 확인에서 자동 확정하지 않고 수동 확인 대상 1건, 상태·이력·알림·Outbox·inbox 변화 없음")
    void s2SkipsHeldWithStartedExamination(ExaminationStatus examinationStatus, String link) {
        Booking booking = booking(Reservation::hold, referral -> {
        });
        UUID linked = "CURRENT".equals(link) ? booking.reservationId() : UUID.randomUUID();
        examination(booking, linked, examinationStatus);
        markStale(booking);
        slot(booking, "BOOKED", booking.reservationId(), null);
        double manualBefore = metric("manual_review");

        scheduler.reconcileReservations();
        scheduler.reconcileReservations();   // 다음 회차에는 대상에서 빠진다

        assertUnchangedHeld(booking, examinationStatus, linked);
        assertThat(consumedEventsSinceStart()).isZero();
        assertThat(failureRepository.findByReservationId(booking.reservationId())).get()
                .satisfies(f -> {
                    assertThat(f.getStatus()).isEqualTo(ReconciliationFailureStatus.MANUAL_REVIEW);
                    assertThat(f.getFailureCount()).isEqualTo(1);
                    assertThat(f.getErrorCode()).isEqualTo(ErrorCode.EXAMINATION_RESERVATION_NOT_CHANGEABLE.name());
                    assertThat(f.getResultType()).isEqualTo(SlotResultType.SLOT_BOOKED.name());
                });
        assertThat(metric("manual_review") - manualBefore).isEqualTo(1);
    }

    @ParameterizedTest(name = "검사 {0}, 검사 연결 = {1}")
    @CsvSource({"COMPLETED, CURRENT", "COMPLETED, PREVIOUS", "IN_PROGRESS, CURRENT", "IN_PROGRESS, PREVIOUS"})
    @DisplayName("사전 확인을 통과해도(검사가 그 뒤 시작된 경우) SlotBooked 최종 확인(잠금)이 확정을 막고, 업무 예외라 즉시 수동 확인 대상이 되며 다음 회차에 재시도하지 않는다")
    void finalLockCheckStopsSlotBookedAndStopsRetry(ExaminationStatus examinationStatus, String link) {
        ReservationReconciliationScheduler staleView = new ReservationReconciliationScheduler(reconciliationService,
                reservationRepository, slotClient, mock(ExaminationRepository.class), failureRecorder, meterRegistry,
                BATCH_SIZE, LOOKBACK_HOURS, STALE_THRESHOLD_MINUTES);
        Booking booking = booking(Reservation::hold, referral -> {
        });
        UUID linked = "CURRENT".equals(link) ? booking.reservationId() : UUID.randomUUID();
        examination(booking, linked, examinationStatus);
        markStale(booking);
        slot(booking, "BOOKED", booking.reservationId(), null);

        staleView.reconcileReservations();
        staleView.reconcileReservations();
        staleView.reconcileReservations();

        assertUnchangedHeld(booking, examinationStatus, linked);
        assertThat(consumedEventsSinceStart()).isZero();   // 복구 트랜잭션 롤백으로 inbox도 남지 않는다
        assertThat(failureRepository.findByReservationId(booking.reservationId())).get()
                .satisfies(f -> {
                    assertThat(f.getStatus()).isEqualTo(ReconciliationFailureStatus.MANUAL_REVIEW);
                    assertThat(f.getFailureCount()).isEqualTo(1);   // 2·3회차는 대상 조회에서 빠져 다시 시도하지 않음
                    assertThat(f.getErrorCode()).isEqualTo(ErrorCode.EXAMINATION_RESERVATION_NOT_CHANGEABLE.name());
                });
    }

    @Test
    @DisplayName("일시 오류는 최초 실행 포함 3회까지 시도(RETRYING 1·2 → MANUAL_REVIEW 3)하고, 4회차부터 대상에서 빠진다")
    void transientFailureRetriesUpToThreeTimes() {
        ReservationReconciliationService failing = mock(ReservationReconciliationService.class);
        doThrow(new QueryTimeoutException("timeout")).when(failing).reconcile(any(), any(), any());
        ReservationReconciliationScheduler flaky = new ReservationReconciliationScheduler(failing, reservationRepository,
                slotClient, examinationRepository, failureRecorder, meterRegistry, BATCH_SIZE, LOOKBACK_HOURS, STALE_THRESHOLD_MINUTES);
        Booking booking = booking(Reservation::hold, referral -> {
        });
        markStale(booking);
        slot(booking, "BOOKED", booking.reservationId(), null);
        double retryBefore = metric("retry_scheduled");
        double manualBefore = metric("manual_review");

        flaky.reconcileReservations();
        assertThat(failure(booking).getStatus()).isEqualTo(ReconciliationFailureStatus.RETRYING);
        assertThat(failure(booking).getFailureCount()).isEqualTo(1);
        flaky.reconcileReservations();
        assertThat(failure(booking).getStatus()).isEqualTo(ReconciliationFailureStatus.RETRYING);
        assertThat(failure(booking).getFailureCount()).isEqualTo(2);
        flaky.reconcileReservations();
        assertThat(failure(booking).getStatus()).isEqualTo(ReconciliationFailureStatus.MANUAL_REVIEW);
        assertThat(failure(booking).getFailureCount()).isEqualTo(3);
        flaky.reconcileReservations();

        verify(failing, times(3)).reconcile(argThat(t -> t.reservationId().equals(booking.reservationId())), any(), any());
        assertThat(failure(booking).getFailureCount()).isEqualTo(3);
        assertThat(failure(booking).getErrorCode()).isEqualTo("QueryTimeoutException");
        assertThat(metric("retry_scheduled") - retryBefore).isEqualTo(2);
        assertThat(metric("manual_review") - manualBefore).isEqualTo(1);
        assertThat(reservationStatus(booking)).isEqualTo(ReservationStatus.HELD);
        assertThat(referralStatus(booking)).isEqualTo(ReferralStatus.PENDING);
        assertThat(outboxTypes(booking)).isEmpty();
    }

    @Test
    @DisplayName("일시 오류 뒤 다음 회차에 복구되면 RETRYING 기록을 지운다")
    void transientFailureThenRecoveryClearsRecord() {
        ReservationReconciliationService flakyOnce = mock(ReservationReconciliationService.class);
        doThrow(new QueryTimeoutException("timeout"))
                .doAnswer(inv -> {
                    reconciliationService.reconcile(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2));
                    return null;
                })
                .when(flakyOnce).reconcile(any(), any(), any());
        ReservationReconciliationScheduler flaky = new ReservationReconciliationScheduler(flakyOnce, reservationRepository,
                slotClient, examinationRepository, failureRecorder, meterRegistry, BATCH_SIZE, LOOKBACK_HOURS, STALE_THRESHOLD_MINUTES);
        Booking booking = booking(Reservation::hold, referral -> {
        });
        markStale(booking);
        slot(booking, "BOOKED", booking.reservationId(), null);

        flaky.reconcileReservations();
        assertThat(failure(booking).getStatus()).isEqualTo(ReconciliationFailureStatus.RETRYING);
        flaky.reconcileReservations();

        assertThat(reservationStatus(booking)).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(failureRepository.findByReservationId(booking.reservationId())).isEmpty();
    }

    @Test
    @DisplayName("같은 예약의 실패를 동시에 기록해도 행은 1건이고 횟수는 모두 반영된다 (UNIQUE + ON CONFLICT)")
    void concurrentFailureRecordsAreDeduplicated() throws Exception {
        Booking booking = booking(Reservation::hold, referral -> {
        });
        int threads = 5;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return failureRecorder.recordFailure(booking.reservationId(), SlotResultType.SLOT_BOOKED,
                            new BaseException(ErrorCode.EXAMINATION_RESERVATION_NOT_CHANGEABLE));
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }

        Long rows = jdbcTemplate.queryForObject("select count(*) from p_reconciliation_failures where reservation_id = ?",
                Long.class, booking.reservationId());
        assertThat(rows).isEqualTo(1);
        assertThat(failure(booking).getFailureCount()).isEqualTo(threads);
        assertThat(failure(booking).getStatus()).isEqualTo(ReconciliationFailureStatus.MANUAL_REVIEW);
    }

    @Test
    @DisplayName("Kafka 경로: 검사가 완료된 의뢰의 HELD 예약에 SlotBooked가 오면 BaseException(재시도 없이 DLT)으로 롤백되고 상태·이력·알림·inbox가 그대로다")
    void kafkaSlotBookedWithCompletedExaminationRollsBack() {
        Booking booking = booking(Reservation::hold, referral -> {
        });
        examination(booking, booking.reservationId(), ExaminationStatus.COMPLETED);

        assertThatThrownBy(() -> slotResultService.handle(UUID.randomUUID(), SlotResultType.SLOT_BOOKED, booking.reservationId()))
                .isInstanceOf(BaseException.class)
                .satisfies(e -> assertThat(((BaseException) e).getErrorCode()).isEqualTo(ErrorCode.EXAMINATION_RESERVATION_NOT_CHANGEABLE));

        assertUnchangedHeld(booking, ExaminationStatus.COMPLETED, booking.reservationId());
        assertThat(consumedEventsSinceStart()).isZero();
    }

    @Test
    @DisplayName("복구 가능 업무 오류(INVALID_REFERRAL_STATUS)는 즉시 수동 확인으로 보내지 않고 최초 실행 포함 3회까지 시도한 뒤 MANUAL_REVIEW로 전환한다")
    void recoverableBusinessErrorRetriesUpToThreeTimes() {
        // 예약은 HELD인데 의뢰가 이미 PENDING이 아니다 → SlotBooked 복구의 의뢰 조건부 확정이 0건이라 INVALID_REFERRAL_STATUS
        Booking booking = booking(Reservation::hold, Referral::confirm);
        markStale(booking);
        slot(booking, "BOOKED", booking.reservationId(), null);
        double retryBefore = metric("retry_scheduled");
        double manualBefore = metric("manual_review");

        scheduler.reconcileReservations();
        assertThat(failure(booking).getStatus()).isEqualTo(ReconciliationFailureStatus.RETRYING);
        assertThat(failure(booking).getFailureCount()).isEqualTo(1);
        assertThat(failure(booking).getErrorCode()).isEqualTo(ErrorCode.INVALID_REFERRAL_STATUS.name());
        scheduler.reconcileReservations();
        assertThat(failure(booking).getStatus()).isEqualTo(ReconciliationFailureStatus.RETRYING);
        scheduler.reconcileReservations();
        assertThat(failure(booking).getStatus()).isEqualTo(ReconciliationFailureStatus.MANUAL_REVIEW);
        scheduler.reconcileReservations();   // 4회차는 대상에서 빠진다

        assertThat(failure(booking).getFailureCount()).isEqualTo(3);
        assertThat(metric("retry_scheduled") - retryBefore).isEqualTo(2);
        assertThat(metric("manual_review") - manualBefore).isEqualTo(1);
        // 매 시도가 롤백돼 예약·의뢰·이력·Outbox·inbox가 그대로다
        assertThat(reservationStatus(booking)).isEqualTo(ReservationStatus.HELD);
        assertThat(referralStatus(booking)).isEqualTo(ReferralStatus.CONFIRMED);
        assertThat(histories(booking)).isEmpty();
        assertThat(outboxTypes(booking)).isEmpty();
        assertThat(consumedEventsSinceStart()).isZero();
    }

    @Test
    @DisplayName("같은 예약에 복구 가능 오류 뒤 복구 불가 오류가 오면 즉시 MANUAL_REVIEW, 이후 복구 가능 오류가 와도 RETRYING으로 내려가지 않는다")
    void manualReviewIsSticky() {
        Booking booking = booking(Reservation::hold, referral -> {
        });
        failureRecorder.recordFailure(booking.reservationId(), SlotResultType.SLOT_BOOKED, new QueryTimeoutException("timeout"));
        assertThat(failure(booking).getStatus()).isEqualTo(ReconciliationFailureStatus.RETRYING);

        failureRecorder.recordFailure(booking.reservationId(), SlotResultType.SLOT_BOOKED,
                new BaseException(ErrorCode.EXAMINATION_RESERVATION_NOT_CHANGEABLE));
        assertThat(failure(booking).getStatus()).isEqualTo(ReconciliationFailureStatus.MANUAL_REVIEW);
        assertThat(failure(booking).getFailureCount()).isEqualTo(2);

        failureRecorder.recordFailure(booking.reservationId(), SlotResultType.SLOT_BOOKED,
                new BaseException(ErrorCode.INVALID_REFERRAL_STATUS));
        assertThat(failure(booking).getStatus()).isEqualTo(ReconciliationFailureStatus.MANUAL_REVIEW);
        Long rows = jdbcTemplate.queryForObject("select count(*) from p_reconciliation_failures where reservation_id = ?",
                Long.class, booking.reservationId());
        assertThat(rows).isEqualTo(1);
    }

    // 자동 복구하지 않은 HELD 예약: 예약·의뢰·검사 그대로, 확정 이력·검사기관 알림·슬롯 명령 Outbox 없음
    private void assertUnchangedHeld(Booking booking, ExaminationStatus examinationStatus, UUID linkedReservationId) {
        Reservation reservation = reservationRepository.findById(booking.reservationId()).orElseThrow();
        assertThat(reservation.getReservationStatus()).isEqualTo(ReservationStatus.HELD);
        assertThat(reservation.getConfirmedAt()).isNull();
        assertThat(referralStatus(booking)).isEqualTo(ReferralStatus.PENDING);
        assertThat(examinationRepository.findByReferralId(booking.referralId())).get()
                .satisfies(e -> {
                    assertThat(e.getStatus()).isEqualTo(examinationStatus);
                    assertThat(e.getReservationId()).isEqualTo(linkedReservationId);
                });
        assertThat(histories(booking)).isEmpty();
        assertThat(institutionNotifications(booking)).isZero();
        assertThat(outboxTypes(booking)).isEmpty();
    }

    private ReconciliationFailure failure(Booking booking) {
        return failureRepository.findByReservationId(booking.reservationId()).orElseThrow();
    }

    private long institutionNotifications(Booking booking) {
        Long count = jdbcTemplate.queryForObject("select count(*) from p_institution_notifications where referral_id = ?",
                Long.class, booking.referralId());
        return count == null ? 0 : count;
    }

    private double metric(String outcome) {
        return meterRegistry.counter("reservation.reconciliation.recovery", "outcome", outcome).count();
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

    // 예약·의뢰가 모두 CONFIRMED인 시작 전 예약
    private Booking confirmedBooking() {
        return booking(reservation -> {
            reservation.hold();
            reservation.confirm();
        }, Referral::confirm);
    }

    // 의뢰의 검사를 지정한 예약 ID·상태로 저장한다 (정리는 cleanUp의 의뢰별 검사 삭제)
    private void examination(Booking booking, UUID linkedReservationId, ExaminationStatus status) {
        Examination examination = Examination.schedule(booking.referralId(), linkedReservationId, UUID.randomUUID());
        ReflectionTestUtils.setField(examination, "status", status);
        examinationRepository.saveAndFlush(examination);
    }

    // 이 테스트가 시작된 뒤 기록된 소비 이력(inbox) 수
    private long consumedEventsSinceStart() {
        Long count = jdbcTemplate.queryForObject("select count(*) from p_consumed_events where processed_at >= ?",
                Long.class, startedAt);
        return count == null ? 0 : count;
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
