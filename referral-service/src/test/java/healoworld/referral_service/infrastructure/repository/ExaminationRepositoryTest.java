package healoworld.referral_service.infrastructure.repository;

import healoworld.referral_service.domain.entity.Examination;
import healoworld.referral_service.domain.enums.ExaminationStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.dao.DataIntegrityViolationException;
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

// 실제 PostgreSQL(docker-compose의 referral_db)에 연결해 제약 조건과 조건부 UPDATE를 검증한다
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class ExaminationRepositoryTest {

    @Autowired
    private ExaminationRepository examinationRepository;

    private Examination save(UUID referralId, UUID reservationId) {
        return examinationRepository.saveAndFlush(Examination.schedule(referralId, reservationId, UUID.randomUUID()));
    }

    private Examination reload(UUID examinationId) {
        return examinationRepository.findById(examinationId).orElseThrow();
    }

    private LocalDateTime now() {
        return LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
    }

    @Test
    @DisplayName("검사는 SCHEDULED로 저장되고 생성·수정 시각이 기록되며 시작·완료 시각은 비어 있다")
    void saveScheduled() {
        UUID referralId = UUID.randomUUID();
        Examination saved = save(referralId, UUID.randomUUID());

        Examination found = examinationRepository.findByReferralId(referralId).orElseThrow();
        assertThat(found.getId()).isEqualTo(saved.getId());
        assertThat(found.getStatus()).isEqualTo(ExaminationStatus.SCHEDULED);
        assertThat(found.getCreatedAt()).isNotNull();
        assertThat(found.getUpdatedAt()).isNotNull();
        assertThat(found.getStartedAt()).isNull();
        assertThat(found.getCompletedAt()).isNull();
    }

    @Test
    @DisplayName("같은 referral_id로 두 번째 검사를 저장하면 UNIQUE 제약으로 거부된다")
    void referralIdIsUnique() {
        UUID referralId = UUID.randomUUID();
        save(referralId, UUID.randomUUID());

        assertThatThrownBy(() -> save(referralId, UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("reservation_id가 없으면 NOT NULL 제약으로 거부된다")
    void reservationIdIsRequired() {
        assertThatThrownBy(() -> save(UUID.randomUUID(), null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("referral_id가 없으면 NOT NULL 제약으로 거부된다")
    void referralIdIsRequired() {

        // 의뢰 ID를 null로 설정해 검사 생성을 시도한다
        assertThatThrownBy(() ->
                save(null, UUID.randomUUID())
        )
                // 필수값이 누락됐으므로 데이터 정합성 위반 예외가 발생해야 한다
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("exam_institution_id가 없으면 NOT NULL 제약으로 거부된다")
    void examInstitutionIdIsRequired() {

        // 검사기관 ID를 null로 설정해 검사 생성을 시도한다
        assertThatThrownBy(() ->
                examinationRepository.saveAndFlush(
                        Examination.schedule(
                                UUID.randomUUID(),
                                UUID.randomUUID(),
                                null
                        )
                )
        )
                // 검사기관 ID는 필수값이므로 저장이 거부되어야 한다
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("시작은 SCHEDULED이고 검증한 예약에 연결돼 있을 때 한 번만 성공하며 started_at을 기록한다")
    void startIfScheduled() {
        UUID reservationId = UUID.randomUUID();
        Examination examination = save(UUID.randomUUID(), reservationId);
        LocalDateTime startedAt = now();

        assertThat(examinationRepository.startIfScheduled(examination.getId(), UUID.randomUUID(), startedAt))
                .as("다른 예약 기준으로 검증한 시작 요청").isZero();
        assertThat(examinationRepository.startIfScheduled(examination.getId(), reservationId, startedAt)).isEqualTo(1);
        assertThat(examinationRepository.startIfScheduled(examination.getId(), reservationId, startedAt.plusMinutes(1)))
                .as("이미 시작된 검사").isZero();

        Examination started = reload(examination.getId());
        assertThat(started.getStatus()).isEqualTo(ExaminationStatus.IN_PROGRESS);
        assertThat(started.getStartedAt()).isEqualTo(startedAt);
        assertThat(started.getUpdatedAt()).isEqualTo(startedAt);
        assertThat(started.getCompletedAt()).isNull();
    }

    @Test
    @DisplayName("완료는 IN_PROGRESS일 때만 성공하며 completed_at을 기록하고 started_at은 유지한다")
    void completeIfInProgress() {
        UUID reservationId = UUID.randomUUID();
        Examination examination = save(UUID.randomUUID(), reservationId);
        LocalDateTime startedAt = now();
        LocalDateTime completedAt = startedAt.plusMinutes(30);

        assertThat(examinationRepository.completeIfInProgress(examination.getId(), completedAt))
                .as("SCHEDULED에서 바로 완료").isZero();

        examinationRepository.startIfScheduled(examination.getId(), reservationId, startedAt);
        assertThat(examinationRepository.completeIfInProgress(examination.getId(), completedAt)).isEqualTo(1);
        assertThat(examinationRepository.completeIfInProgress(examination.getId(), completedAt.plusMinutes(1)))
                .as("이미 완료된 검사").isZero();

        Examination completed = reload(examination.getId());
        assertThat(completed.getStatus()).isEqualTo(ExaminationStatus.COMPLETED);
        assertThat(completed.getStartedAt()).isEqualTo(startedAt);
        assertThat(completed.getCompletedAt()).isEqualTo(completedAt);
    }

    @Test
    @DisplayName("예약 연결 변경은 SCHEDULED일 때만 성공하고 검사 ID와 의뢰 ID는 그대로다")
    void changeReservationIfScheduled() {
        UUID referralId = UUID.randomUUID();
        UUID reservationA = UUID.randomUUID();
        UUID reservationB = UUID.randomUUID();
        UUID institutionB = UUID.randomUUID();
        Examination examination = save(referralId, reservationA);

        assertThat(examinationRepository.changeReservationIfScheduled(examination.getId(), reservationB, institutionB,
                now())).isEqualTo(1);

        Examination relinked = reload(examination.getId());
        assertThat(relinked.getReferralId()).isEqualTo(referralId);
        assertThat(relinked.getReservationId()).isEqualTo(reservationB);
        assertThat(relinked.getExamInstitutionId()).isEqualTo(institutionB);
        assertThat(relinked.getStatus()).isEqualTo(ExaminationStatus.SCHEDULED);

        examinationRepository.startIfScheduled(examination.getId(), reservationB, now());
        assertThat(examinationRepository.changeReservationIfScheduled(examination.getId(), UUID.randomUUID(),
                UUID.randomUUID(), now())).as("시작된 검사").isZero();
        assertThat(reload(examination.getId()).getReservationId()).isEqualTo(reservationB);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED) // 스레드마다 별도 트랜잭션이 커밋된 데이터를 봐야 하므로 테스트 트랜잭션을 끈다
    @DisplayName("동시에 10번 완료 처리해도 실제 갱신은 1건뿐이다")
    void completeConcurrently() throws Exception {
        UUID reservationId = UUID.randomUUID();
        Examination examination = save(UUID.randomUUID(), reservationId);
        examinationRepository.startIfScheduled(examination.getId(), reservationId, now());
        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(1);

        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < threadCount; i++) {
                results.add(executor.submit(() -> {
                    ready.await();
                    return examinationRepository.completeIfInProgress(examination.getId(), now());
                }));
            }
            ready.countDown();

            int updatedRows = 0;
            for (Future<Integer> result : results) {
                updatedRows += result.get();
            }
            assertThat(updatedRows).isEqualTo(1);
            assertThat(reload(examination.getId()).getStatus()).isEqualTo(ExaminationStatus.COMPLETED);
        } finally {
            executor.shutdown();
            examinationRepository.deleteById(examination.getId());
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("재예약 연결 변경과 검사 시작이 동시에 실행되면 둘 중 하나만 성공하고 결과가 서로 맞는다")
    void changeReservationAndStartConcurrently() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        List<UUID> createdIds = new ArrayList<>();

        try {
            for (int i = 0; i < 20; i++) {
                UUID reservationA = UUID.randomUUID();
                UUID reservationB = UUID.randomUUID();
                Examination examination = save(UUID.randomUUID(), reservationA);
                createdIds.add(examination.getId());
                CountDownLatch ready = new CountDownLatch(1);

                // 시작 요청은 예약 A를 기준으로 검증했고, 같은 순간 재예약이 예약 B로 연결을 바꾼다
                Future<Integer> start = executor.submit(() -> {
                    ready.await();
                    return examinationRepository.startIfScheduled(examination.getId(), reservationA, now());
                });
                Future<Integer> change = executor.submit(() -> {
                    ready.await();
                    return examinationRepository.changeReservationIfScheduled(examination.getId(), reservationB,
                            UUID.randomUUID(), now());
                });
                ready.countDown();

                int started = start.get();
                int changed = change.get();
                Examination result = reload(examination.getId());

                assertThat(started + changed).as("정확히 하나만 성공").isEqualTo(1);
                if (started == 1) {
                    assertThat(result.getStatus()).isEqualTo(ExaminationStatus.IN_PROGRESS);
                    assertThat(result.getReservationId()).isEqualTo(reservationA);
                } else {
                    assertThat(result.getStatus()).isEqualTo(ExaminationStatus.SCHEDULED);
                    assertThat(result.getReservationId()).isEqualTo(reservationB);
                }
            }
        } finally {
            executor.shutdown();
            examinationRepository.deleteAllById(createdIds);
        }
    }
}
