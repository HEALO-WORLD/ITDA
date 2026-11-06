package healoworld.referral_service.application.service;

import healoworld.referral_service.application.event.result.SlotResultType;
import healoworld.referral_service.application.service.IncidentProcessingService.Outcome;
import healoworld.referral_service.domain.entity.Examination;
import healoworld.referral_service.domain.entity.IncidentReferral;
import healoworld.referral_service.domain.entity.Referral;
import healoworld.referral_service.domain.entity.ReferralHistory;
import healoworld.referral_service.domain.entity.Reservation;
import healoworld.referral_service.domain.enums.ExaminationStatus;
import healoworld.referral_service.domain.enums.Reason;
import healoworld.referral_service.domain.enums.ReferralStatus;
import healoworld.referral_service.domain.enums.ReservationStatus;
import healoworld.referral_service.domain.enums.RoleType;
import healoworld.referral_service.global.config.QuerydslConfig;
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
import healoworld.referral_service.presentation.dto.response.ReservationResponse;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

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
        SlotCommandOutbox.class,
        QuerydslConfig.class,
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
    @Autowired private ExaminationRepository examinationRepository;
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

    // ---------- 도우미 ----------

    // 예약(14~15시)을 덮는 장애를 등록하고 후속 작업 1건을 처리한다
    private IncidentReferral blockByIncident() {
        UUID incidentId = equipmentIncidentService.register(UUID.randomUUID(), equipmentId, UUID.randomUUID(),
                "가상 장애 사유", LocalDateTime.now(), startsAt.minusHours(1), startsAt.plusHours(2));
        incidentIds.add(incidentId);
        assertThat(equipmentIncidentService.createPendingReferrals(incidentId)).isEqualTo(1);
        IncidentReferral task = incidentReferralRepository.findAll().stream()
                .filter(t -> t.getIncident().getId().equals(incidentId))
                .findFirst().orElseThrow();
        assertThat(incidentProcessingService.process(task.getId())).isEqualTo(Outcome.SUCCEEDED);
        return task;
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
