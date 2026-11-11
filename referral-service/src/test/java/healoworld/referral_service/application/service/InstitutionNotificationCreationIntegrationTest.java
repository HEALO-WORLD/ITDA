package healoworld.referral_service.application.service;

import healoworld.referral_service.application.event.result.SlotResultType;
import healoworld.referral_service.domain.entity.InstitutionNotification;
import healoworld.referral_service.domain.entity.Referral;
import healoworld.referral_service.domain.entity.Reservation;
import healoworld.referral_service.domain.enums.ReferralStatus;
import healoworld.referral_service.domain.enums.ReservationStatus;
import healoworld.referral_service.domain.enums.RoleType;
import healoworld.referral_service.global.config.ClockConfig;
import healoworld.referral_service.global.config.QuerydslConfig;
import healoworld.referral_service.global.exception.BaseException;
import healoworld.referral_service.global.exception.ErrorCode;
import healoworld.referral_service.global.inbox.ConsumedEventId;
import healoworld.referral_service.global.inbox.ConsumedEventRepository;
import healoworld.referral_service.global.outbox.OutboxEventRepository;
import healoworld.referral_service.global.outbox.SlotCommandOutbox;
import healoworld.referral_service.infrastructure.repository.EquipmentIncidentLockRepository;
import healoworld.referral_service.infrastructure.repository.ExaminationRepository;
import healoworld.referral_service.infrastructure.repository.InstitutionNotificationRepository;
import healoworld.referral_service.infrastructure.repository.ReferralHistoryRepository;
import healoworld.referral_service.infrastructure.repository.ReferralRepository;
import healoworld.referral_service.infrastructure.repository.ReservationRepository;
import healoworld.referral_service.support.PostgresContainerSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

/**
 * 검사기관 공통 알림 생성(예약 확정·취소·재예약)을 실제 PostgreSQL(Testcontainers)에서 검증한다.
 * <p>
 * - SlotResultService.handle()(Kafka Consumer가 호출하는 진입점)과 ReservationService.cancelReservation()을 실제로 호출한다
 * - 각 처리가 실제로 커밋·롤백되므로 테스트 트랜잭션을 끄고, 이 테스트가 만든 행만 끝날 때 지운다
 * - 환자 이름은 가상 값이며, 알림 제목·내용에 들어가지 않는지 확인한다
 */
@DataJpaTest
@Import({
        SlotResultService.class,
        InstitutionNotificationService.class,
        EquipmentIncidentService.class,
        EquipmentIncidentLockRepository.class,
        ExaminationService.class,
        ReservationService.class,
        ReferralHistoryService.class,
        SlotCommandOutbox.class,
        QuerydslConfig.class, ClockConfig.class,
        InstitutionNotificationCreationIntegrationTest.JsonConfig.class
})
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
// 이 테스트 전용 컨텍스트가 하나 더 캐시되므로 연결 풀을 작게 둔다 (전체 테스트 실행 시 PostgreSQL max_connections 초과 방지)
// 이 테스트는 순차 처리만 하므로 연결 3개로 충분하다
@TestPropertySource(properties = {
        "spring.datasource.hikari.maximum-pool-size=3",
        "spring.datasource.hikari.minimum-idle=1"
})
class InstitutionNotificationCreationIntegrationTest extends PostgresContainerSupport {

    @TestConfiguration(proxyBeanMethods = false)
    static class JsonConfig {
        @Bean
        JsonMapper jsonMapper() {
            return JsonMapper.builder().build();
        }
    }

    private static final String PATIENT_NAME = "가상환자";
    private static final DateTimeFormatter SCHEDULE = DateTimeFormatter.ofPattern("M월 d일 HH:mm");

    @Autowired private SlotResultService slotResultService;
    @Autowired private ReservationService reservationService;
    @Autowired private InstitutionNotificationRepository institutionNotificationRepository;
    @Autowired private ReferralRepository referralRepository;
    @Autowired private ReservationRepository reservationRepository;
    @Autowired private ReferralHistoryRepository referralHistoryRepository;
    @Autowired private ExaminationRepository examinationRepository;
    @Autowired private OutboxEventRepository outboxEventRepository;
    @Autowired private ConsumedEventRepository consumedEventRepository;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbcTemplate;

    // 확정 트랜잭션 롤백 재현용. 기본은 실제 메서드를 그대로 호출한다
    @MockitoSpyBean private EquipmentIncidentService equipmentIncidentService;

    private final List<UUID> referralIds = new ArrayList<>();
    private final List<UUID> reservationIds = new ArrayList<>();
    private final List<UUID> eventIds = new ArrayList<>();

    private final UUID institutionA = UUID.randomUUID();
    private final UUID institutionB = UUID.randomUUID();

    // 의뢰 1건 (의뢰기관 사용자·기관)
    private record Case(UUID referralId, UUID userId, UUID requesterInstitutionId) {
    }

    @AfterEach
    void cleanUp() {
        for (UUID referralId : referralIds) {
            jdbcTemplate.update("delete from p_institution_notifications where referral_id = ?", referralId);
            referralHistoryRepository.deleteAll(referralHistoryRepository.findAllByReferralIdOrderByOccurredAtAsc(referralId));
            examinationRepository.findByReferralId(referralId).ifPresent(examinationRepository::delete);
        }
        outboxEventRepository.deleteAll(outboxEventRepository.findAll().stream()
                .filter(e -> reservationIds.contains(e.getAggregateId())).toList());
        eventIds.forEach(id -> consumedEventRepository.deleteById(new ConsumedEventId(id, SlotResultService.CONSUMER)));
        reservationRepository.deleteAllById(reservationIds);
        referralRepository.deleteAllById(referralIds);
    }

    // ---------- 준비 ----------

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }

    private Case newReferral() {
        UUID userId = UUID.randomUUID();
        UUID requesterInstitutionId = UUID.randomUUID();
        UUID referralId = tx().execute(status -> referralRepository.save(Referral.create(requesterInstitutionId, userId,
                UUID.randomUUID(), PATIENT_NAME, UUID.randomUUID())).getId());
        referralIds.add(referralId);
        return new Case(referralId, userId, requesterInstitutionId);
    }

    private static LocalDateTime daysLater(int days, int hour) {
        return LocalDateTime.now().plusDays(days).withHour(hour).withMinute(0).withSecond(0).withNano(0);
    }

    // 예약 시도를 만든다. 첫 시도면 의뢰에 연결하고, 재시도면 재예약(NEEDS_RESCHEDULE → PENDING)으로 현재 예약을 바꾼다
    private UUID attempt(Case referralCase, int attemptNo, UUID examInstitutionId, LocalDateTime startsAt, boolean held) {
        UUID reservationId = tx().execute(status -> {
            Referral referral = referralRepository.findById(referralCase.referralId()).orElseThrow();
            Reservation reservation = Reservation.create(referral, attemptNo, UUID.randomUUID(), examInstitutionId,
                    UUID.randomUUID(), startsAt, startsAt, startsAt.plusMinutes(30));
            if (held) {
                reservation.hold();
            }
            reservationRepository.save(reservation);
            if (attemptNo == 1) {
                referral.assignReservation(reservation.getId());
            } else {
                referral.reschedule(reservation.getId());
            }
            return reservation.getId();
        });
        reservationIds.add(reservationId);
        return reservationId;
    }

    private UUID heldAttempt(Case referralCase, int attemptNo, UUID examInstitutionId, LocalDateTime startsAt) {
        return attempt(referralCase, attemptNo, examInstitutionId, startsAt, true);
    }

    private UUID handle(SlotResultType type, UUID reservationId) {
        UUID eventId = UUID.randomUUID();
        handle(type, reservationId, eventId);
        return eventId;
    }

    private void handle(SlotResultType type, UUID reservationId, UUID eventId) {
        eventIds.add(eventId);
        slotResultService.handle(eventId, type, reservationId);
    }

    // 첫 예약 확정 → 장비 장애 등으로 차단(의뢰 NEEDS_RESCHEDULE) 상태를 만든다
    private UUID confirmedThenBlocked(Case referralCase, UUID examInstitutionId, LocalDateTime startsAt) {
        UUID first = heldAttempt(referralCase, 1, examInstitutionId, startsAt);
        handle(SlotResultType.SLOT_BOOKED, first);
        handle(SlotResultType.SLOTS_BLOCKED, first);
        assertThat(referralStatus(referralCase)).isEqualTo(ReferralStatus.NEEDS_RESCHEDULE);
        return first;
    }

    private void cancel(Case referralCase, UUID reservationId) {
        reservationService.cancelReservation(referralCase.userId(), referralCase.requesterInstitutionId(),
                RoleType.REQUESTER, reservationId);
    }

    // ---------- 조회 ----------

    private List<InstitutionNotification> notificationsOf(Case referralCase) {
        return institutionNotificationRepository.findAll().stream()
                .filter(n -> n.getReferralId().equals(referralCase.referralId()))
                .toList();
    }

    private List<InstitutionNotification> notificationsOf(Case referralCase, UUID institutionId) {
        return notificationsOf(referralCase).stream().filter(n -> n.getInstitutionId().equals(institutionId)).toList();
    }

    private ReservationStatus reservationStatus(UUID reservationId) {
        return reservationRepository.findById(reservationId).orElseThrow().getReservationStatus();
    }

    private ReferralStatus referralStatus(Case referralCase) {
        return referralRepository.findById(referralCase.referralId()).orElseThrow().getReferralStatus();
    }

    private static void assertNoPersonalData(InstitutionNotification notification) {
        assertThat(notification.getTitle()).doesNotContain(PATIENT_NAME);
        assertThat(notification.getMessage()).doesNotContain(PATIENT_NAME);
    }

    private static void assertErrorCode(Throwable thrown, ErrorCode expected) {
        assertThat(thrown).isInstanceOf(BaseException.class);
        assertThat(((BaseException) thrown).getErrorCode()).isEqualTo(expected);
    }

    // ---------- 4. 신규 예약 확정 ----------

    @Nested
    @DisplayName("신규 예약 확정")
    class Confirmed {

        @Test
        @DisplayName("첫 예약 SlotBooked 성공 → 예약 검사기관에 RESERVATION_CONFIRMED 정확히 1건 (원인 이벤트·예약·일시 포함, 환자 이름 없음)")
        void firstConfirmation() {
            Case referralCase = newReferral();
            LocalDateTime startsAt = daysLater(3, 10);
            UUID reservationId = heldAttempt(referralCase, 1, institutionA, startsAt);

            UUID eventId = handle(SlotResultType.SLOT_BOOKED, reservationId);

            assertThat(reservationStatus(reservationId)).isEqualTo(ReservationStatus.CONFIRMED);
            assertThat(notificationsOf(referralCase)).singleElement().satisfies(n -> {
                assertThat(n.getInstitutionId()).isEqualTo(institutionA);
                assertThat(n.getType()).isEqualTo(InstitutionNotificationService.RESERVATION_CONFIRMED);
                assertThat(n.getReservationId()).isEqualTo(reservationId);
                assertThat(n.getSourceEventId()).isEqualTo(eventId);
                assertThat(n.getTitle()).isEqualTo("새로운 검사 예약이 확정되었습니다.");
                assertThat(n.getMessage()).isEqualTo(startsAt.format(SCHEDULE)
                        + " 검사 예약이 확정되었습니다. 예약 내역을 확인해 주세요.");
                assertNoPersonalData(n);
            });
        }

        @Test
        @DisplayName("SlotHeld로 HELD가 된 단계에서는 알림이 없다")
        void heldCreatesNothing() {
            Case referralCase = newReferral();
            UUID reservationId = attempt(referralCase, 1, institutionA, daysLater(3, 10), false);

            handle(SlotResultType.SLOT_HELD, reservationId);

            assertThat(reservationStatus(reservationId)).isEqualTo(ReservationStatus.HELD);
            assertThat(notificationsOf(referralCase)).isEmpty();
        }

        @Test
        @DisplayName("확정 실패(SlotBookFailed·HoldExpired)는 알림이 없다")
        void confirmFailureCreatesNothing() {
            Case bookFailed = newReferral();
            UUID first = heldAttempt(bookFailed, 1, institutionA, daysLater(3, 10));
            handle(SlotResultType.SLOT_BOOK_FAILED, first);
            Case expired = newReferral();
            UUID second = heldAttempt(expired, 1, institutionA, daysLater(3, 11));
            handle(SlotResultType.HOLD_EXPIRED, second);

            assertThat(reservationStatus(first)).isEqualTo(ReservationStatus.FAILED);
            assertThat(reservationStatus(second)).isEqualTo(ReservationStatus.FAILED);
            assertThat(notificationsOf(bookFailed)).isEmpty();
            assertThat(notificationsOf(expired)).isEmpty();
        }

        @Test
        @DisplayName("의뢰 확정에 실패해 확정 트랜잭션이 롤백되면 알림도 남지 않는다 (예약은 HELD 그대로)")
        void referralConfirmFailureRollsBack() {
            Case referralCase = newReferral();
            UUID reservationId = heldAttempt(referralCase, 1, institutionA, daysLater(3, 10));
            // 의뢰가 이미 PENDING이 아니면 confirmIfPending이 0건 → INVALID_REFERRAL_STATUS로 예약 변경까지 롤백
            jdbcTemplate.update("update p_referrals set referral_status = 'CONFIRMED' where id = ?", referralCase.referralId());

            Throwable thrown = catchThrowable(() -> handle(SlotResultType.SLOT_BOOKED, reservationId));

            assertErrorCode(thrown, ErrorCode.INVALID_REFERRAL_STATUS);
            assertThat(reservationStatus(reservationId)).isEqualTo(ReservationStatus.HELD);
            assertThat(notificationsOf(referralCase)).isEmpty();
        }

        @Test
        @DisplayName("알림 저장 뒤 같은 확정 트랜잭션의 후속 단계가 실패하면 알림도 함께 롤백된다")
        void laterFailureRollsBackNotification() {
            Case referralCase = newReferral();
            UUID reservationId = heldAttempt(referralCase, 1, institutionA, daysLater(3, 10));
            // 알림 생성 다음 단계(장비 장애 작업 연결)에서 실패
            // linkConfirmedReservation은 MANDATORY라 스텁 등록 호출도 트랜잭션 프록시를 지나므로 트랜잭션 안에서 등록한다
            tx().executeWithoutResult(status -> doThrow(new IllegalStateException("가상 실패"))
                    .when(equipmentIncidentService).linkConfirmedReservation(any(), any(), any(), any(), any()));

            Throwable thrown = catchThrowable(() -> handle(SlotResultType.SLOT_BOOKED, reservationId));

            assertThat(thrown).isInstanceOf(IllegalStateException.class);
            assertThat(reservationStatus(reservationId)).isEqualTo(ReservationStatus.HELD);
            assertThat(referralStatus(referralCase)).isEqualTo(ReferralStatus.PENDING);
            assertThat(notificationsOf(referralCase)).isEmpty();
        }

        @Test
        @DisplayName("같은 SlotBooked 재전달(같은 eventId)·새 eventId의 늦은 중복 모두 알림이 늘지 않는다")
        void duplicateEventCreatesNoMore() {
            Case referralCase = newReferral();
            UUID reservationId = heldAttempt(referralCase, 1, institutionA, daysLater(3, 10));
            UUID eventId = handle(SlotResultType.SLOT_BOOKED, reservationId);

            slotResultService.handle(eventId, SlotResultType.SLOT_BOOKED, reservationId);   // inbox에서 걸러짐
            handle(SlotResultType.SLOT_BOOKED, reservationId);                              // 이미 CONFIRMED라 무시

            assertThat(notificationsOf(referralCase)).hasSize(1);
        }
    }

    // ---------- 5. 예약 취소 ----------

    @Nested
    @DisplayName("예약 취소")
    class Canceled {

        @Test
        @DisplayName("확정 예약 취소 성공 → 취소된 예약의 검사기관에 RESERVATION_CANCELED 정확히 1건 (원인 = ReleaseSlot Outbox 이벤트)")
        void cancelSuccess() {
            Case referralCase = newReferral();
            LocalDateTime startsAt = daysLater(3, 10);
            UUID reservationId = heldAttempt(referralCase, 1, institutionA, startsAt);
            handle(SlotResultType.SLOT_BOOKED, reservationId);

            cancel(referralCase, reservationId);

            UUID releaseEventId = outboxEventRepository.findAll().stream()
                    .filter(e -> reservationId.equals(e.getAggregateId()) && "ReleaseSlot".equals(e.getEventType()))
                    .map(e -> e.getId()).findFirst().orElseThrow();
            assertThat(notificationsOf(referralCase, institutionA))
                    .filteredOn(n -> n.getType().equals(InstitutionNotificationService.RESERVATION_CANCELED))
                    .singleElement().satisfies(n -> {
                        assertThat(n.getReservationId()).isEqualTo(reservationId);
                        assertThat(n.getSourceEventId()).isEqualTo(releaseEventId);
                        assertThat(n.getTitle()).isEqualTo("검사 예약이 취소되었습니다.");
                        assertThat(n.getMessage()).isEqualTo(startsAt.format(SCHEDULE)
                                + " 검사 예약이 취소되었습니다. 검사 일정을 확인해 주세요.");
                        assertNoPersonalData(n);
                    });
            // 확정 1 + 취소 1, 다른 기관에는 없음
            assertThat(notificationsOf(referralCase)).hasSize(2).allMatch(n -> n.getInstitutionId().equals(institutionA));
        }

        @Test
        @DisplayName("같은 예약을 다시 취소하면 거부되고 취소 알림은 1건 그대로다")
        void cancelTwice() {
            Case referralCase = newReferral();
            UUID reservationId = heldAttempt(referralCase, 1, institutionA, daysLater(3, 10));
            handle(SlotResultType.SLOT_BOOKED, reservationId);
            cancel(referralCase, reservationId);

            Throwable thrown = catchThrowable(() -> cancel(referralCase, reservationId));

            assertThat(thrown).isInstanceOf(BaseException.class);
            assertThat(notificationsOf(referralCase))
                    .filteredOn(n -> n.getType().equals(InstitutionNotificationService.RESERVATION_CANCELED)).hasSize(1);
        }

        @Test
        @DisplayName("취소 거부(2시간 이내·검사 시작·다른 기관 사용자)는 취소 알림이 없고 예약은 CONFIRMED 그대로다")
        void cancelRejectedCreatesNothing() {
            Case soon = newReferral();
            UUID soonId = heldAttempt(soon, 1, institutionA, LocalDateTime.now().plusHours(1));
            handle(SlotResultType.SLOT_BOOKED, soonId);
            Case started = newReferral();
            UUID startedId = heldAttempt(started, 1, institutionA, daysLater(3, 10));
            handle(SlotResultType.SLOT_BOOKED, startedId);
            jdbcTemplate.update("update p_examinations set status = 'IN_PROGRESS' where referral_id = ?", started.referralId());
            Case other = newReferral();
            UUID otherId = heldAttempt(other, 1, institutionA, daysLater(3, 11));
            handle(SlotResultType.SLOT_BOOKED, otherId);

            assertErrorCode(catchThrowable(() -> cancel(soon, soonId)), ErrorCode.RESERVATION_CANCEL_DEADLINE_PASSED);
            assertErrorCode(catchThrowable(() -> cancel(started, startedId)), ErrorCode.EXAMINATION_CANCEL_NOT_ALLOWED);
            assertErrorCode(catchThrowable(() -> reservationService.cancelReservation(other.userId(), UUID.randomUUID(),
                    RoleType.REQUESTER, otherId)), ErrorCode.RESERVATION_NOT_FOUND);

            for (Case referralCase : List.of(soon, started, other)) {
                assertThat(notificationsOf(referralCase)).extracting(InstitutionNotification::getType)
                        .containsExactly(InstitutionNotificationService.RESERVATION_CONFIRMED);
            }
            assertThat(List.of(soonId, startedId, otherId)).allMatch(id -> reservationStatus(id) == ReservationStatus.CONFIRMED);
        }
    }

    // ---------- 6. 재예약 확정 ----------

    @Nested
    @DisplayName("재예약 확정")
    class Rescheduled {

        @Test
        @DisplayName("같은 기관으로 재예약 확정 → 그 기관에 RESERVATION_RESCHEDULED 1건(이전·변경 일정), 추가 확정 알림 없음")
        void sameInstitution() {
            Case referralCase = newReferral();
            LocalDateTime before = daysLater(3, 10);
            LocalDateTime after = daysLater(4, 15);
            confirmedThenBlocked(referralCase, institutionA, before);
            UUID second = heldAttempt(referralCase, 2, institutionA, after);
            int beforeCount = notificationsOf(referralCase).size();

            UUID eventId = handle(SlotResultType.SLOT_BOOKED, second);

            List<InstitutionNotification> added = notificationsOf(referralCase).stream()
                    .filter(n -> n.getReservationId().equals(second)).toList();
            assertThat(notificationsOf(referralCase)).hasSize(beforeCount + 1);
            assertThat(added).singleElement().satisfies(n -> {
                assertThat(n.getInstitutionId()).isEqualTo(institutionA);
                assertThat(n.getType()).isEqualTo(InstitutionNotificationService.RESERVATION_RESCHEDULED);
                assertThat(n.getSourceEventId()).isEqualTo(eventId);
                assertThat(n.getTitle()).isEqualTo("검사 예약 일정이 변경되었습니다.");
                assertThat(n.getMessage()).isEqualTo("검사 예약 일정이 " + before.format(SCHEDULE) + "에서 "
                        + after.format(SCHEDULE) + "(으)로 변경되었습니다. 예약 내역을 확인해 주세요.");
                assertNoPersonalData(n);
            });
            assertThat(notificationsOf(referralCase, institutionB)).isEmpty();
        }

        @Test
        @DisplayName("다른 기관으로 재예약 확정 → 이전 기관 RESERVATION_REASSIGNED(이전 예약 기준)·새 기관 RESERVATION_CONFIRMED 각 1건")
        void differentInstitution() {
            Case referralCase = newReferral();
            LocalDateTime before = daysLater(3, 10);
            LocalDateTime after = daysLater(4, 15);
            UUID first = confirmedThenBlocked(referralCase, institutionA, before);
            UUID second = heldAttempt(referralCase, 2, institutionB, after);

            handle(SlotResultType.SLOT_BOOKED, second);

            assertThat(notificationsOf(referralCase, institutionA)).extracting(InstitutionNotification::getType)
                    .containsExactlyInAnyOrder(InstitutionNotificationService.RESERVATION_CONFIRMED,
                            InstitutionNotificationService.RESERVATION_REASSIGNED);
            assertThat(notificationsOf(referralCase, institutionA))
                    .filteredOn(n -> n.getType().equals(InstitutionNotificationService.RESERVATION_REASSIGNED))
                    .singleElement().satisfies(n -> {
                        assertThat(n.getReservationId()).isEqualTo(first);
                        assertThat(n.getMessage()).isEqualTo(before.format(SCHEDULE)
                                + " 검사 예약이 다른 검사기관으로 변경되었습니다. 검사 일정을 확인해 주세요.");
                        assertNoPersonalData(n);
                    });
            assertThat(notificationsOf(referralCase, institutionB)).singleElement().satisfies(n -> {
                assertThat(n.getType()).isEqualTo(InstitutionNotificationService.RESERVATION_CONFIRMED);
                assertThat(n.getReservationId()).isEqualTo(second);
                assertThat(n.getMessage()).startsWith(after.format(SCHEDULE));
            });
        }

        @Test
        @DisplayName("재예약이 확정 전에 실패(HOLD 실패)하면 알림이 없고, 그다음 재예약은 실제 마지막 확정 예약과 비교한다")
        void failedAttemptIsSkipped() {
            Case referralCase = newReferral();
            LocalDateTime before = daysLater(3, 10);
            confirmedThenBlocked(referralCase, institutionA, before);
            UUID failed = attempt(referralCase, 2, institutionB, daysLater(4, 9), false);
            handle(SlotResultType.SLOT_HOLD_FAILED, failed);
            assertThat(referralStatus(referralCase)).isEqualTo(ReferralStatus.NEEDS_RESCHEDULE);
            assertThat(notificationsOf(referralCase, institutionB)).isEmpty();

            UUID third = heldAttempt(referralCase, 3, institutionA, daysLater(5, 11));
            handle(SlotResultType.SLOT_BOOKED, third);

            // 기관 B에는 확정된 적이 없으므로 아무 알림도 없고, 기관 A는 첫 예약과 같은 기관이라 일정 변경 1건
            assertThat(notificationsOf(referralCase, institutionB)).isEmpty();
            assertThat(notificationsOf(referralCase, institutionA)).filteredOn(n -> n.getReservationId().equals(third))
                    .extracting(InstitutionNotification::getType)
                    .containsExactly(InstitutionNotificationService.RESERVATION_RESCHEDULED);
        }

        @Test
        @DisplayName("재예약 SlotBooked가 다시 와도 기관별 알림이 늘지 않는다")
        void duplicateRescheduleEvent() {
            Case referralCase = newReferral();
            confirmedThenBlocked(referralCase, institutionA, daysLater(3, 10));
            UUID second = heldAttempt(referralCase, 2, institutionB, daysLater(4, 15));
            UUID eventId = handle(SlotResultType.SLOT_BOOKED, second);
            int count = notificationsOf(referralCase).size();

            slotResultService.handle(eventId, SlotResultType.SLOT_BOOKED, second);
            handle(SlotResultType.SLOT_BOOKED, second);

            assertThat(notificationsOf(referralCase)).hasSize(count);
            assertThat(notificationsOf(referralCase, institutionB)).hasSize(1);
        }
    }
}
