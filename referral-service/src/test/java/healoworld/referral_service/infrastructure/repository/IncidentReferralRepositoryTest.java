package healoworld.referral_service.infrastructure.repository;

import healoworld.referral_service.domain.entity.EquipmentIncident;
import healoworld.referral_service.domain.entity.IncidentReferral;
import healoworld.referral_service.domain.enums.IncidentProcessingStatus;
import healoworld.referral_service.global.config.QuerydslConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

// 테스트 전용 PostgreSQL(itda_referral_test)에 연결해 제약 조건, 상태별 조회, 조건부 UPDATE를 검증한다
// 동시성 테스트를 제외한 테스트는 끝나면 롤백된다
@DataJpaTest
@Import(QuerydslConfig.class)
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class IncidentReferralRepositoryTest {

    private static final PageRequest PAGE = PageRequest.of(0, 20);

    @Autowired
    private EquipmentIncidentRepository equipmentIncidentRepository;

    @Autowired
    private IncidentReferralRepository incidentReferralRepository;

    // DB(TIMESTAMP)는 마이크로초까지 저장하므로 비교·잠금 값도 같은 정밀도로 맞춘다
    private LocalDateTime now() {
        return LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
    }

    private EquipmentIncident saveIncident() {
        LocalDateTime start = now();
        return equipmentIncidentRepository.saveAndFlush(EquipmentIncident.occur(UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), "테스트용 가상 장애 사유", start, start, start.plusHours(2)));
    }

    private IncidentReferral save(EquipmentIncident incident) {
        return incidentReferralRepository.saveAndFlush(
                IncidentReferral.pending(incident, UUID.randomUUID(), UUID.randomUUID()));
    }

    private IncidentReferral reload(UUID id) {
        return incidentReferralRepository.findById(id).orElseThrow();
    }

    // PENDING → PROCESSING → FAILED (nextRetryAt 지정)
    private void fail(UUID id, LocalDateTime nextRetryAt) {
        LocalDateTime lockedUntil = now().plusMinutes(5);
        incidentReferralRepository.claim(id, now(), lockedUntil);
        incidentReferralRepository.markFailed(id, lockedUntil, "가상 실패 원인", nextRetryAt, now());
    }

    @Test
    @DisplayName("영향 의뢰 작업은 PENDING·재시도 0회·잠금 없음으로 저장된다")
    void savePending() {
        EquipmentIncident incident = saveIncident();
        IncidentReferral saved = save(incident);

        IncidentReferral found = reload(saved.getId());
        assertThat(found.getProcessingStatus()).isEqualTo(IncidentProcessingStatus.PENDING);
        assertThat(found.getIncident().getId()).isEqualTo(incident.getId());
        assertThat(found.getRetryCount()).isZero();
        assertThat(found.getLockedUntil()).isNull();
        assertThat(found.getNextRetryAt()).isNull();
        assertThat(found.getProcessedAt()).isNull();
        assertThat(found.getCreatedAt()).isNotNull();
        assertThat(found.getUpdatedAt()).isNotNull();
    }

    @Test
    @DisplayName("같은 장애·같은 예약 작업을 두 번 저장하면 UNIQUE 제약으로 거부되고, 다른 장애라면 허용된다")
    void incidentAndReservationIsUnique() {
        EquipmentIncident incident = saveIncident();
        UUID reservationId = UUID.randomUUID();
        incidentReferralRepository.saveAndFlush(IncidentReferral.pending(incident, UUID.randomUUID(), reservationId));

        assertThat(incidentReferralRepository.existsByIncident_IdAndReservationId(incident.getId(), reservationId))
                .isTrue();
        assertThatThrownBy(() -> incidentReferralRepository.saveAndFlush(
                IncidentReferral.pending(incident, UUID.randomUUID(), reservationId)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("같은 예약이라도 다른 장애의 작업이면 저장된다")
    void sameReservationOtherIncident() {
        UUID reservationId = UUID.randomUUID();
        UUID referralId = UUID.randomUUID();
        incidentReferralRepository.saveAndFlush(IncidentReferral.pending(saveIncident(), referralId, reservationId));
        incidentReferralRepository.saveAndFlush(IncidentReferral.pending(saveIncident(), referralId, reservationId));

        assertThat(incidentReferralRepository.count()).isGreaterThanOrEqualTo(2);
    }

    // PostgreSQL은 한 트랜잭션에서 오류가 나면 이후 SQL을 모두 거부하므로 컬럼마다 별도 테스트로 실행한다
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"incident_id", "referral_id", "reservation_id"})
    @DisplayName("필수 컬럼이 없으면 NOT NULL 제약으로 거부된다")
    void requiredColumns(String column) {
        EquipmentIncident incident = saveIncident();
        IncidentReferral job = switch (column) {
            case "incident_id" -> IncidentReferral.pending(null, UUID.randomUUID(), UUID.randomUUID());
            case "referral_id" -> IncidentReferral.pending(incident, null, UUID.randomUUID());
            default -> IncidentReferral.pending(incident, UUID.randomUUID(), null);
        };

        assertThatThrownBy(() -> incidentReferralRepository.saveAndFlush(job))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("장애 ID로 그 장애의 영향 의뢰만 조회한다")
    void findByIncidentId() {
        EquipmentIncident incident = saveIncident();
        IncidentReferral a = save(incident);
        IncidentReferral b = save(incident);
        save(saveIncident()); // 다른 장애

        Page<IncidentReferral> page = incidentReferralRepository.findByIncident_Id(incident.getId(), PAGE);

        assertThat(page.getContent()).extracting(IncidentReferral::getId)
                .containsExactlyInAnyOrder(a.getId(), b.getId());
    }

    @Test
    @DisplayName("PENDING·PROCESSING·SUCCEEDED·FAILED 네 상태가 저장되고 상태별로 조회·집계된다")
    void statusesAreStoredAndQueried() {
        EquipmentIncident incident = saveIncident();
        IncidentReferral pending = save(incident);
        IncidentReferral processing = save(incident);
        IncidentReferral succeeded = save(incident);
        IncidentReferral failed = save(incident);
        LocalDateTime lockedUntil = now().plusMinutes(5);

        incidentReferralRepository.claim(processing.getId(), now(), lockedUntil);
        incidentReferralRepository.claim(succeeded.getId(), now(), lockedUntil);
        incidentReferralRepository.markSucceeded(succeeded.getId(), lockedUntil, "TEST_RESULT", now());
        fail(failed.getId(), null);

        assertThat(reload(pending.getId()).getProcessingStatus()).isEqualTo(IncidentProcessingStatus.PENDING);
        assertThat(reload(processing.getId()).getProcessingStatus()).isEqualTo(IncidentProcessingStatus.PROCESSING);
        assertThat(reload(succeeded.getId()).getProcessingStatus()).isEqualTo(IncidentProcessingStatus.SUCCEEDED);
        assertThat(reload(failed.getId()).getProcessingStatus()).isEqualTo(IncidentProcessingStatus.FAILED);

        for (IncidentProcessingStatus status : IncidentProcessingStatus.values()) {
            assertThat(incidentReferralRepository.countByIncident_IdAndProcessingStatus(incident.getId(), status))
                    .as(status.name()).isEqualTo(1);
        }
        assertThat(incidentReferralRepository.findByIncident_IdAndProcessingStatus(incident.getId(),
                IncidentProcessingStatus.FAILED, PAGE).getContent())
                .extracting(IncidentReferral::getId).containsExactly(failed.getId());
    }

    @Test
    @DisplayName("선점은 PENDING에서 한 번만 성공하고, 잠금이 유효한 동안 다른 작업자는 가져갈 수 없다")
    void claimPending() {
        IncidentReferral job = save(saveIncident());
        LocalDateTime now = now();
        LocalDateTime lockedUntil = now.plusMinutes(5);

        assertThat(incidentReferralRepository.claim(job.getId(), now, lockedUntil)).isEqualTo(1);
        assertThat(incidentReferralRepository.claim(job.getId(), now.plusMinutes(1), now.plusMinutes(6)))
                .as("잠금 유효 중 다른 작업자").isZero();

        IncidentReferral claimed = reload(job.getId());
        assertThat(claimed.getProcessingStatus()).isEqualTo(IncidentProcessingStatus.PROCESSING);
        assertThat(claimed.getLockedUntil()).isEqualTo(lockedUntil);
        assertThat(claimed.getRetryCount()).as("첫 처리는 재시도가 아님").isZero();
    }

    @Test
    @DisplayName("잠금이 만료된 PROCESSING은 다른 작업자가 다시 선점할 수 있고, 늦게 끝난 원래 작업자는 결과를 덮어쓰지 못한다")
    void expiredLockCanBeReclaimed() {
        IncidentReferral job = save(saveIncident());
        LocalDateTime now = now();
        LocalDateTime firstLock = now.plusMinutes(5);
        LocalDateTime secondLock = now.plusMinutes(11);

        incidentReferralRepository.claim(job.getId(), now, firstLock);
        assertThat(incidentReferralRepository.claim(job.getId(), now.plusMinutes(6), secondLock))
                .as("잠금 만료 후 재선점").isEqualTo(1);

        assertThat(incidentReferralRepository.markSucceeded(job.getId(), firstLock, null, now.plusMinutes(7)))
                .as("만료된 잠금을 가진 작업자의 완료 처리").isZero();
        assertThat(incidentReferralRepository.markFailed(job.getId(), firstLock, "늦은 실패", null, now.plusMinutes(7)))
                .as("만료된 잠금을 가진 작업자의 실패 처리").isZero();
        assertThat(reload(job.getId()).getLockedUntil()).isEqualTo(secondLock);

        assertThat(incidentReferralRepository.markSucceeded(job.getId(), secondLock, "TEST_RESULT",
                now.plusMinutes(8))).isEqualTo(1);
        IncidentReferral done = reload(job.getId());
        assertThat(done.getProcessingStatus()).isEqualTo(IncidentProcessingStatus.SUCCEEDED);
        assertThat(done.getLockedUntil()).isNull();
        assertThat(done.getProcessedAt()).isEqualTo(now.plusMinutes(8));
        assertThat(done.getResultCode()).isEqualTo("TEST_RESULT");
    }

    @Test
    @DisplayName("실패 처리는 잠금을 풀고 실패 원인·다음 재시도 시각을 남기며, FAILED를 다시 선점하면 재시도 횟수가 1 오른다")
    void failThenRetry() {
        IncidentReferral job = save(saveIncident());
        LocalDateTime nextRetryAt = now().plusMinutes(10);

        fail(job.getId(), nextRetryAt);
        IncidentReferral failed = reload(job.getId());
        assertThat(failed.getProcessingStatus()).isEqualTo(IncidentProcessingStatus.FAILED);
        assertThat(failed.getLockedUntil()).isNull();
        assertThat(failed.getLastError()).isEqualTo("가상 실패 원인");
        assertThat(failed.getNextRetryAt()).isEqualTo(nextRetryAt);
        assertThat(failed.getProcessedAt()).isNull();
        assertThat(failed.getRetryCount()).isZero();

        LocalDateTime lockedUntil = now().plusMinutes(5);
        assertThat(incidentReferralRepository.claim(job.getId(), now(), lockedUntil)).isEqualTo(1);
        assertThat(reload(job.getId()).getRetryCount()).isEqualTo(1);
        assertThat(incidentReferralRepository.markSucceeded(job.getId(), lockedUntil, null, now())).isEqualTo(1);
        assertThat(reload(job.getId()).getProcessingStatus()).isEqualTo(IncidentProcessingStatus.SUCCEEDED);
    }

    @Test
    @DisplayName("SUCCEEDED 작업은 다시 선점되지 않는다 (성공한 작업을 재처리하지 않음)")
    void succeededIsNotClaimed() {
        IncidentReferral job = save(saveIncident());
        LocalDateTime lockedUntil = now().plusMinutes(5);
        incidentReferralRepository.claim(job.getId(), now(), lockedUntil);
        incidentReferralRepository.markSucceeded(job.getId(), lockedUntil, null, now());

        assertThat(incidentReferralRepository.claim(job.getId(), now().plusHours(1), now().plusHours(2))).isZero();
        assertThat(reload(job.getId()).getProcessingStatus()).isEqualTo(IncidentProcessingStatus.SUCCEEDED);
    }

    @Test
    @DisplayName("재처리 대상은 재시도 시각이 된 FAILED와 잠금이 만료된 PROCESSING뿐이다")
    void findRetryTargets() {
        EquipmentIncident incident = saveIncident();
        LocalDateTime now = now();
        IncidentReferral failedDue = save(incident);
        IncidentReferral failedNoSchedule = save(incident);
        IncidentReferral failedLater = save(incident);
        IncidentReferral processingExpired = save(incident);
        IncidentReferral processingActive = save(incident);
        IncidentReferral pending = save(incident);
        IncidentReferral succeeded = save(incident);
        IncidentReferral otherIncidentFailed = save(saveIncident());

        fail(failedDue.getId(), now.minusMinutes(1));
        fail(failedNoSchedule.getId(), null);
        fail(failedLater.getId(), now.plusHours(1));
        fail(otherIncidentFailed.getId(), now.minusMinutes(1));
        incidentReferralRepository.claim(processingExpired.getId(), now.minusMinutes(10), now.minusMinutes(5));
        incidentReferralRepository.claim(processingActive.getId(), now, now.plusMinutes(5));
        LocalDateTime lock = now.plusMinutes(5);
        incidentReferralRepository.claim(succeeded.getId(), now, lock);
        incidentReferralRepository.markSucceeded(succeeded.getId(), lock, null, now);

        List<IncidentReferral> targets = incidentReferralRepository.findRetryTargets(incident.getId(), now, PAGE);

        assertThat(targets).extracting(IncidentReferral::getId)
                .containsExactlyInAnyOrder(failedDue.getId(), failedNoSchedule.getId(), processingExpired.getId())
                .doesNotContain(failedLater.getId(), processingActive.getId(), pending.getId(), succeeded.getId(),
                        otherIncidentFailed.getId());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED) // 스레드마다 별도 트랜잭션이 커밋된 데이터를 봐야 하므로 테스트 트랜잭션을 끈다
    @DisplayName("10개 작업자가 같은 작업을 동시에 선점해도 1개만 성공한다")
    void claimConcurrently() throws Exception {
        // 롤백되지 않고 실제로 커밋되므로, 이 테스트가 만든 행만 마지막에 ID로 지운다 (TestDatabaseGuard가 테스트 DB임을 보장)
        EquipmentIncident incident = saveIncident();
        IncidentReferral job = save(incident);
        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(1);

        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < threadCount; i++) {
                results.add(executor.submit(() -> {
                    ready.await();
                    LocalDateTime now = now();
                    return incidentReferralRepository.claim(job.getId(), now, now.plusMinutes(5));
                }));
            }
            ready.countDown();

            int claimed = 0;
            for (Future<Integer> result : results) {
                claimed += result.get();
            }
            assertThat(claimed).isEqualTo(1);
            IncidentReferral result = reload(job.getId());
            assertThat(result.getProcessingStatus()).isEqualTo(IncidentProcessingStatus.PROCESSING);
            assertThat(result.getRetryCount()).isZero();
        } finally {
            executor.shutdown();
            incidentReferralRepository.deleteById(job.getId());   // FK 때문에 작업 → 장애 순서로 지운다
            equipmentIncidentRepository.deleteById(incident.getId());
        }
    }

    @Test
    @DisplayName("lockedUntil에 나노초가 있어도 claim과 markSucceeded가 같은 값을 넘기면 잠금 비교가 일치한다")
    void lockedUntilWithNanoseconds() {
        IncidentReferral job = save(saveIncident());
        LocalDateTime lockedUntil = now().plusMinutes(5).withNano(123_456_789);

        assertThat(incidentReferralRepository.claim(job.getId(), now(), lockedUntil)).isEqualTo(1);

        // DB(TIMESTAMP)는 마이크로초까지만 저장하므로 다시 읽은 값은 Java의 원래 값과 다르다
        assertThat(reload(job.getId()).getLockedUntil()).isNotEqualTo(lockedUntil)
                .isCloseTo(lockedUntil, within(1, ChronoUnit.MICROS));
        // 같은 나노초 값을 다시 바인딩하면 DB 쪽에서도 같은 정밀도로 바뀌어 비교되므로 일치한다
        assertThat(incidentReferralRepository.markSucceeded(job.getId(), lockedUntil, null, now())).isEqualTo(1);
    }
}
