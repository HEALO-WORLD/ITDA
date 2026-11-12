package healoworld.referral_service.application.service;

import healoworld.referral_service.domain.entity.Referral;
import healoworld.referral_service.domain.enums.Reason;
import healoworld.referral_service.domain.enums.ReferralStatus;
import healoworld.referral_service.domain.enums.ReservationStatus;
import healoworld.referral_service.global.config.ClockConfig;
import healoworld.referral_service.global.config.QuerydslConfig;
import healoworld.referral_service.global.dto.ApiResponse;
import healoworld.referral_service.global.exception.BaseException;
import healoworld.referral_service.global.exception.ErrorCode;
import healoworld.referral_service.global.outbox.SlotCommandOutbox;
import healoworld.referral_service.infrastructure.client.institution.EquipmentOwnerResponse;
import healoworld.referral_service.infrastructure.client.institution.InstitutionClient;
import healoworld.referral_service.infrastructure.client.institution.PartnershipCheckResponse;
import healoworld.referral_service.infrastructure.client.slot.SlotClient;
import healoworld.referral_service.infrastructure.client.slot.SlotReservationContextResponse;
import healoworld.referral_service.infrastructure.repository.ReferralRepository;
import healoworld.referral_service.presentation.dto.request.CreateReferralRequest;
import healoworld.referral_service.presentation.dto.request.CreateReservationRequest;
import healoworld.referral_service.presentation.dto.response.ReferralResponse;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;

/**
 * 의뢰 생성(POST /api/v1/referrals)을 실제 DB에서 검증한다.
 * <p>
 * - 의뢰·첫 예약·예약 연결·HoldRequested Outbox·생성 이력이 한 트랜잭션으로 저장·롤백되는지 확인한다
 * - 같은 멱등 키의 동시 요청은 하나만 생성되고, 저장이 겹친 나머지는 UNIQUE 위반으로 409를 받는다 (SIT_007-2, 의도된 동작)
 *   먼저 생성된 뒤에 도착한 같은 본문의 요청은 기존 의뢰를 받으므로, 동시 요청의 응답은 201 1건 이상 + 나머지 409다
 * - institution-service 호출만 Mock이고, 조회·저장은 실제 Postgres에서 실행한다
 * - 각 요청이 실제로 커밋하므로 테스트 트랜잭션을 끄고, 끝날 때 이 테스트가 만든 행만 지운다
 */
@DataJpaTest
@Import({
        ReferralService.class,
        ReservationService.class,
        ReferralHistoryService.class,
        InstitutionNotificationService.class,
        SlotCommandOutbox.class,
        QuerydslConfig.class, ClockConfig.class,
        ReferralCreationIntegrationTest.JsonConfig.class
})
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
// 이 클래스만 쓰는 설정 조합이라 컨텍스트가 따로 캐시된다. 끝나면 닫아 테스트 Postgres 커넥션을 돌려준다 (too many clients 방지)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ReferralCreationIntegrationTest extends PostgresContainerSupport {

    @TestConfiguration(proxyBeanMethods = false)
    static class JsonConfig {
        @Bean
        JsonMapper jsonMapper() {
            return JsonMapper.builder().build();
        }
    }

    private static final int THREADS = 10;

    @Autowired private ReferralService referralService;
    @Autowired private ReferralRepository referralRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PlatformTransactionManager transactionManager;

    // 협력 관계·장비 소유 확인 (institution-service)
    @MockitoBean private InstitutionClient institutionClient;

    // 슬롯 검증 (slot-service). request()가 만든 슬롯을 slots에 등록해 두고 그대로 응답한다
    @MockitoBean private SlotClient slotClient;
    private final Map<UUID, SlotReservationContextResponse> slots = new ConcurrentHashMap<>();

    // 마지막 단계(생성 이력)에서 실패를 일으켜 전체 롤백을 확인한다. 테스트마다 스텁이 초기화된다
    @MockitoSpyBean private ReferralHistoryService referralHistoryService;

    private final UUID userId = UUID.randomUUID();
    private final UUID institutionId = UUID.randomUUID();
    private final UUID examInstitutionId = UUID.randomUUID();
    private final UUID equipmentId = UUID.randomUUID();
    private final LocalDateTime startsAt =
            LocalDateTime.now().plusDays(3).withHour(10).withMinute(0).withSecond(0).withNano(0);

    // 정리 대상: 이 테스트에서 쓴 멱등 키
    private final Set<UUID> idempotencyKeys = new HashSet<>();

    @BeforeEach
    void setUp() {
        given(institutionClient.check(any(), any())).willReturn(new PartnershipCheckResponse(true));
        given(institutionClient.getEquipmentOwner(any())).willAnswer(inv -> ApiResponse.ok("장비 소속 기관 조회에 성공했습니다.",
                new EquipmentOwnerResponse(inv.getArgument(0), examInstitutionId)));
        given(slotClient.getReservationContext(any())).willAnswer(inv -> slots.get(inv.<UUID>getArgument(0)));
    }

    @AfterEach
    void cleanUp() {
        for (UUID key : idempotencyKeys) {
            List<UUID> referralIds = jdbcTemplate.queryForList(
                    "select id from p_referrals where idempotency_key = ?", UUID.class, key);
            for (UUID referralId : referralIds) {
                jdbcTemplate.update("delete from p_referral_histories where referral_id = ?", referralId);
                jdbcTemplate.update("delete from p_outbox_events where aggregate_id in "
                        + "(select id from p_reservations where referral_id = ?)", referralId);
                jdbcTemplate.update("delete from p_reservations where referral_id = ?", referralId);
                jdbcTemplate.update("delete from p_referrals where id = ?", referralId);
            }
        }
    }

    // ---------- 한 트랜잭션 저장 ----------

    @Test
    @DisplayName("생성하면 의뢰 PENDING·첫 예약 REQUESTED·예약 연결·HoldRequested Outbox·생성 이력이 함께 저장된다")
    void createsReferralWithReservationOutboxAndHistory() {
        CreateReferralRequest request = request(newKey(), "가상환자");

        ReferralResponse response = create(request);

        Map<String, Object> referral = jdbcTemplate.queryForMap(
                "select id, referral_status, current_reservation_id, referrer_id from p_referrals where idempotency_key = ?",
                request.getIdempotencyKey());
        assertThat(referral.get("id")).isEqualTo(response.getId());
        assertThat(referral.get("referral_status")).isEqualTo(ReferralStatus.PENDING.name());
        assertThat(referral.get("referrer_id")).isEqualTo(userId);

        Map<String, Object> reservation = jdbcTemplate.queryForMap(
                "select id, attempt_no, reservation_status, slot_id from p_reservations where referral_id = ?",
                response.getId());
        UUID reservationId = (UUID) reservation.get("id");
        assertThat(referral.get("current_reservation_id")).isEqualTo(reservationId);
        assertThat(reservation.get("attempt_no")).isEqualTo(1);
        assertThat(reservation.get("reservation_status")).isEqualTo(ReservationStatus.REQUESTED.name());
        assertThat(reservation.get("slot_id")).isEqualTo(request.getReservation().getSlotId());

        // HoldRequested: slot-commands 토픽, key = slotId, 아직 미발행 (OutboxPublisher가 발행)
        Map<String, Object> outbox = jdbcTemplate.queryForMap(
                "select id, topic, message_key, event_type, published_at, retry_count from p_outbox_events where aggregate_id = ?",
                reservationId);
        assertThat(outbox.get("topic")).isEqualTo("slot-commands");
        assertThat(outbox.get("message_key")).isEqualTo(request.getReservation().getSlotId().toString());
        assertThat(outbox.get("event_type")).isEqualTo("HoldRequested");
        assertThat(outbox.get("published_at")).isNull();
        assertThat(outbox.get("retry_count")).isEqualTo(0);

        // 생성 이력: (없음) → PENDING, HoldRequested와 같은 eventId, 요청자 기록
        Map<String, Object> history = jdbcTemplate.queryForMap(
                "select reservation_id, event_id, from_status, to_status, reason, changed_by from p_referral_histories where referral_id = ?",
                response.getId());
        assertThat(history.get("reservation_id")).isEqualTo(reservationId);
        assertThat(history.get("event_id")).isEqualTo(outbox.get("id"));
        assertThat(history.get("from_status")).isNull();
        assertThat(history.get("to_status")).isEqualTo(ReferralStatus.PENDING.name());
        assertThat(history.get("reason")).isEqualTo(Reason.REFERRAL_CREATED.name());
        assertThat(history.get("changed_by")).isEqualTo(userId);
    }

    @Test
    @DisplayName("마지막 단계(생성 이력)가 실패하면 의뢰·예약·Outbox가 모두 롤백되고, 같은 키로 다시 요청하면 정상 생성된다")
    void rollsBackEverythingWhenHistoryFails() {
        CreateReferralRequest request = request(newKey(), "가상환자");
        doThrow(new IllegalStateException("이력 저장 실패"))
                .when(referralHistoryService).recordHistory(any(), any(), any(), any(), any(), any(), any());

        assertThatThrownBy(() -> create(request)).isInstanceOf(IllegalStateException.class);

        assertThat(countReferrals(request.getIdempotencyKey())).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from p_reservations where slot_id = ?", Integer.class,
                request.getReservation().getSlotId())).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from p_outbox_events where message_key = ?", Integer.class,
                request.getReservation().getSlotId().toString())).isZero();

        // 실패한 요청이 멱등 키를 차지하지 않으므로, 클라이언트 재시도는 새로 생성된다
        org.mockito.Mockito.reset(referralHistoryService);
        ReferralResponse retried = create(request);
        assertThat(retried.getReferralStatus()).isEqualTo(ReferralStatus.PENDING);
        assertCreatedOnce(request.getIdempotencyKey());
    }

    // ---------- 멱등성 ----------

    @Test
    @DisplayName("같은 멱등 키·같은 본문으로 순서대로 2번 요청하면 같은 의뢰를 받고 의뢰·예약·Outbox·이력은 1건씩이다")
    void sequentialSameKeyReturnsSameReferral() {
        CreateReferralRequest request = request(newKey(), "가상환자");

        ReferralResponse first = create(request);
        ReferralResponse second = create(request);

        assertThat(second.getId()).isEqualTo(first.getId());
        assertCreatedOnce(request.getIdempotencyKey());
    }

    @Test
    @DisplayName("같은 멱등 키·같은 본문으로 10건을 동시에 요청하면 의뢰는 1건이고, 응답은 같은 의뢰의 201 1건 이상 + 나머지 409다")
    void concurrentSameKeyCreatesOnceAndOthersConflict() throws Exception {
        CreateReferralRequest request = request(newKey(), "가상환자");

        List<Object> outcomes = runConcurrently(THREADS, i -> request);

        List<ReferralResponse> succeeded = outcomes.stream()
                .filter(ReferralResponse.class::isInstance).map(ReferralResponse.class::cast).toList();
        assertThat(succeeded).isNotEmpty();
        assertThat(succeeded.stream().map(ReferralResponse::getId).distinct()).hasSize(1);
        assertThat(outcomes).allSatisfy(outcome -> {
            if (!(outcome instanceof ReferralResponse)) {
                assertThat(outcome).isInstanceOf(BaseException.class);
                assertThat(((BaseException) outcome).getErrorCode()).isEqualTo(ErrorCode.REFERRAL_IDEMPOTENCY_CONFLICT);
            }
        });
        assertCreatedOnce(request.getIdempotencyKey());
    }

    @Test
    @DisplayName("[결정적] 같은 멱등 키의 의뢰가 다른 트랜잭션에서 저장 중(미커밋)이면 생성은 기다렸다가, 그 커밋 뒤 409이고 의뢰는 1건이다")
    void overlappingSaveReturnsConflict() throws Exception {
        CreateReferralRequest request = request(newKey(), "가상환자");
        CountDownLatch saved = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            // 먼저 온 요청: 같은 키·같은 본문의 의뢰를 저장했지만 아직 커밋하지 않은 상태
            Future<?> first = executor.submit(() -> tx().executeWithoutResult(status -> {
                referralRepository.saveAndFlush(Referral.create(request.getReferringInstitutionId(), userId,
                        request.getExamTypeId(), request.getPatientName(), request.getIdempotencyKey()));
                saved.countDown();
                await(commit);
            }));
            assertThat(saved.await(10, TimeUnit.SECONDS)).isTrue();

            // 뒤에 온 요청: 미커밋 의뢰는 조회되지 않아 저장을 시도하고, UNIQUE 인덱스에서 앞 트랜잭션을 기다린다
            Future<ReferralResponse> second = executor.submit(() -> create(request));
            assertThatThrownBy(() -> second.get(1, TimeUnit.SECONDS)).isInstanceOf(TimeoutException.class);

            commit.countDown();
            first.get(10, TimeUnit.SECONDS);

            assertThatThrownBy(() -> second.get(10, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .cause()
                    .isInstanceOf(BaseException.class)
                    .extracting(e -> ((BaseException) e).getErrorCode())
                    .isEqualTo(ErrorCode.REFERRAL_IDEMPOTENCY_CONFLICT);
            // 뒤 요청은 예약·Outbox·이력을 남기지 않고 롤백된다
            assertThat(countReferrals(request.getIdempotencyKey())).isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject(
                    "select count(*) from p_reservations where slot_id = ?", Integer.class,
                    request.getReservation().getSlotId())).isZero();
            assertThat(jdbcTemplate.queryForObject(
                    "select count(*) from p_outbox_events where message_key = ?", Integer.class,
                    request.getReservation().getSlotId().toString())).isZero();
        } finally {
            commit.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("같은 멱등 키에 서로 다른 본문을 동시에 요청하면 의뢰는 1건이고, 저장된 본문과 다른 요청은 모두 409다")
    void concurrentSameKeyDifferentBodyConflicts() throws Exception {
        UUID key = newKey();
        CreateReferralRequest bodyA = request(key, "가상환자A");
        CreateReferralRequest bodyB = withPatientName(bodyA, "가상환자B");

        List<Object> outcomes = runConcurrently(THREADS, i -> i % 2 == 0 ? bodyA : bodyB);

        String storedName = jdbcTemplate.queryForObject(
                "select patient_name from p_referrals where idempotency_key = ?", String.class, key);
        for (int i = 0; i < THREADS; i++) {
            String sentName = (i % 2 == 0 ? bodyA : bodyB).getPatientName();
            Object outcome = outcomes.get(i);
            if (outcome instanceof ReferralResponse response) {
                // 성공은 저장된 본문과 같은 요청뿐이다
                assertThat(sentName).as("성공한 요청 #%d의 본문", i).isEqualTo(storedName);
                assertThat(response.getPatientName()).isEqualTo(storedName);
            } else {
                assertThat(outcome).as("실패한 요청 #%d", i).isInstanceOf(BaseException.class);
                assertThat(((BaseException) outcome).getErrorCode()).isEqualTo(ErrorCode.REFERRAL_IDEMPOTENCY_CONFLICT);
            }
        }
        assertCreatedOnce(key);
    }

    @Test
    @DisplayName("서로 다른 멱등 키의 동시 요청은 각각 생성된다 (멱등 키가 다르면 서로 영향이 없다)")
    void concurrentDifferentKeysAllCreated() throws Exception {
        List<CreateReferralRequest> requests = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            requests.add(request(newKey(), "가상환자" + i));
        }

        List<Object> outcomes = runConcurrently(THREADS, requests::get);

        assertThat(outcomes).allSatisfy(outcome -> assertThat(outcome).isInstanceOf(ReferralResponse.class));
        assertThat(outcomes.stream().map(o -> ((ReferralResponse) o).getId()).distinct()).hasSize(THREADS);
        requests.forEach(r -> assertCreatedOnce(r.getIdempotencyKey()));
    }

    // ---------- 도우미 ----------

    private ReferralResponse create(CreateReferralRequest request) {
        return referralService.createReferral(userId, institutionId, request);
    }

    // 모든 요청을 같은 순간에 시작시키고, 요청 순서대로 응답 또는 예외를 돌려준다
    private List<Object> runConcurrently(int count, java.util.function.IntFunction<CreateReferralRequest> requestOf)
            throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(count);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<CompletableFuture<Object>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                CreateReferralRequest request = requestOf.apply(i);
                futures.add(CompletableFuture.supplyAsync(() -> {
                    await(start);
                    try {
                        return create(request);
                    } catch (RuntimeException e) {
                        return e;
                    }
                }, executor));
            }
            start.countDown();
            List<Object> outcomes = new ArrayList<>();
            for (CompletableFuture<Object> future : futures) {
                outcomes.add(future.get(30, TimeUnit.SECONDS));
            }
            return outcomes;
        } finally {
            executor.shutdownNow();
        }
    }

    private void assertCreatedOnce(UUID idempotencyKey) {
        UUID referralId = jdbcTemplate.queryForObject(
                "select id from p_referrals where idempotency_key = ?", UUID.class, idempotencyKey);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from p_reservations where referral_id = ?", Integer.class, referralId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from p_outbox_events where event_type = 'HoldRequested' and aggregate_id in "
                        + "(select id from p_reservations where referral_id = ?)", Integer.class, referralId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from p_referral_histories where referral_id = ?", Integer.class, referralId)).isEqualTo(1);
    }

    private int countReferrals(UUID idempotencyKey) {
        return jdbcTemplate.queryForObject(
                "select count(*) from p_referrals where idempotency_key = ?", Integer.class, idempotencyKey);
    }

    private UUID newKey() {
        UUID key = UUID.randomUUID();
        idempotencyKeys.add(key);
        return key;
    }

    private CreateReferralRequest request(UUID idempotencyKey, String patientName) {
        UUID slotId = UUID.randomUUID();
        UUID examTypeId = UUID.randomUUID();
        CreateReservationRequest reservation = CreateReservationRequest.builder()
                .slotId(slotId)
                .examInstitutionId(examInstitutionId)
                .equipmentId(equipmentId)
                .examDate(startsAt)
                .startTime(startsAt)
                .endTime(startsAt.plusHours(1))
                .build();
        slots.put(slotId, new SlotReservationContextResponse(slotId, equipmentId, examTypeId,
                startsAt.toLocalDate(), startsAt.toLocalTime(), startsAt.plusHours(1).toLocalTime()));
        return CreateReferralRequest.builder()
                .referringInstitutionId(institutionId)
                .examTypeId(examTypeId)
                .patientName(patientName)
                .idempotencyKey(idempotencyKey)
                .reservation(reservation)
                .build();
    }

    private CreateReferralRequest withPatientName(CreateReferralRequest source, String patientName) {
        return CreateReferralRequest.builder()
                .referringInstitutionId(source.getReferringInstitutionId())
                .examTypeId(source.getExamTypeId())
                .patientName(patientName)
                .idempotencyKey(source.getIdempotencyKey())
                .reservation(source.getReservation())
                .build();
    }

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("대기 시간 초과");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
