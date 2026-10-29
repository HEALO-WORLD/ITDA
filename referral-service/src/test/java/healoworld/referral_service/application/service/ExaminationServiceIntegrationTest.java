package healoworld.referral_service.application.service;

import healoworld.referral_service.domain.entity.Examination;
import healoworld.referral_service.domain.enums.ExaminationStatus;
import healoworld.referral_service.global.exception.BaseException;
import healoworld.referral_service.global.exception.ErrorCode;
import healoworld.referral_service.infrastructure.repository.ExaminationRepository;
import healoworld.referral_service.infrastructure.repository.ExaminationResultRepository;
import healoworld.referral_service.presentation.dto.response.ExaminationResponse;
import healoworld.referral_service.presentation.dto.response.ExaminationResultCreateResponse;
import healoworld.referral_service.presentation.dto.response.ExaminationStatusChangeResponse;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Service + 실제 PostgreSQL 통합 테스트 (Service의 @Transactional이 테스트 트랜잭션에 참여하고 테스트 후 롤백된다)
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ExaminationService.class, ExaminationResultService.class})
class ExaminationServiceIntegrationTest {

    @Autowired
    private ExaminationService examinationService;

    @Autowired
    private ExaminationResultService examinationResultService;

    @Autowired
    private ExaminationRepository examinationRepository;

    @Autowired
    private ExaminationResultRepository examinationResultRepository;

    private static void assertErrorCode(ThrowingCallable call, ErrorCode errorCode) {
        assertThatThrownBy(call).isInstanceOf(BaseException.class)
                .extracting("errorCode").isEqualTo(errorCode);
    }

    private Examination reload(UUID examinationId) {
        return examinationRepository.findById(examinationId).orElseThrow();
    }

    private LocalDateTime now() {
        return LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
    }

    // 검사 시작은 의뢰·예약 검증 연동 전까지 Service에서 막혀 있으므로, 시작 이후 상태는 Repository로 준비한다
    private Examination examinationIn(ExaminationStatus status) {
        UUID reservationId = UUID.randomUUID();
        UUID examinationId = examinationService.createScheduled(UUID.randomUUID(), reservationId, UUID.randomUUID());
        if (status != ExaminationStatus.SCHEDULED) {
            examinationRepository.startIfScheduled(examinationId, reservationId, now());
        }
        if (status == ExaminationStatus.COMPLETED) {
            examinationRepository.completeIfInProgress(examinationId, now());
        }
        return reload(examinationId);
    }

    // ===== 최초 예약 확정 → 검사 생성 =====

    @Test
    @DisplayName("최초 예약 확정 시 SCHEDULED 검사가 생성된다")
    void createScheduled() {
        UUID referralId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        UUID institutionId = UUID.randomUUID();

        UUID examinationId = examinationService.createScheduled(referralId, reservationId, institutionId);

        Examination created = reload(examinationId);
        assertThat(created.getReferralId()).isEqualTo(referralId);
        assertThat(created.getReservationId()).isEqualTo(reservationId);
        assertThat(created.getExamInstitutionId()).isEqualTo(institutionId);
        assertThat(created.getStatus()).isEqualTo(ExaminationStatus.SCHEDULED);
    }

    @Test
    @DisplayName("같은 확정 처리가 다시 실행되면 새로 만들지 않고 기존 검사 ID를 돌려준다")
    void createScheduledIsIdempotent() {
        UUID referralId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        UUID institutionId = UUID.randomUUID();

        UUID first = examinationService.createScheduled(referralId, reservationId, institutionId);
        UUID second = examinationService.createScheduled(referralId, reservationId, institutionId);

        assertThat(second).isEqualTo(first);
        assertThat(examinationRepository.findByReferralId(referralId)).isPresent();
    }

    @Test
    @DisplayName("이미 다른 예약으로 검사가 있는 의뢰에 생성을 요청하면 EXAMINATION_ALREADY_EXISTS")
    void createScheduledWithOtherReservation() {
        UUID referralId = UUID.randomUUID();
        UUID institutionId = UUID.randomUUID();
        examinationService.createScheduled(referralId, UUID.randomUUID(), institutionId);

        assertErrorCode(() -> examinationService.createScheduled(referralId, UUID.randomUUID(), institutionId),
                ErrorCode.EXAMINATION_ALREADY_EXISTS);
    }

    // ===== 재예약 확정 → 기존 검사 수정 =====

    @Test
    @DisplayName("재예약 시 기존 검사 ID·의뢰 ID를 유지하고 예약·검사기관만 바꾸며 SCHEDULED를 유지한다")
    void updateReservation() {
        UUID referralId = UUID.randomUUID();
        UUID examinationId = examinationService.createScheduled(referralId, UUID.randomUUID(), UUID.randomUUID());
        UUID reservationB = UUID.randomUUID();
        UUID institutionB = UUID.randomUUID();

        UUID result = examinationService.updateReservation(referralId, reservationB, institutionB);

        Examination relinked = reload(examinationId);
        assertThat(result).isEqualTo(examinationId);
        assertThat(relinked.getReferralId()).isEqualTo(referralId);
        assertThat(relinked.getReservationId()).isEqualTo(reservationB);
        assertThat(relinked.getExamInstitutionId()).isEqualTo(institutionB);
        assertThat(relinked.getStatus()).isEqualTo(ExaminationStatus.SCHEDULED);
    }

    @Test
    @DisplayName("같은 예약으로 다시 연결 요청하면 아무것도 바꾸지 않는다 (updated_at 유지)")
    void updateReservationIsIdempotent() {
        UUID referralId = UUID.randomUUID();
        UUID reservationB = UUID.randomUUID();
        UUID institutionB = UUID.randomUUID();
        UUID examinationId = examinationService.createScheduled(referralId, UUID.randomUUID(), UUID.randomUUID());
        examinationService.updateReservation(referralId, reservationB, institutionB);
        LocalDateTime updatedAt = reload(examinationId).getUpdatedAt();

        examinationService.updateReservation(referralId, reservationB, institutionB);

        assertThat(reload(examinationId).getUpdatedAt()).isEqualTo(updatedAt);
    }

    @ParameterizedTest
    @EnumSource(value = ExaminationStatus.class, names = {"IN_PROGRESS", "COMPLETED"})
    @DisplayName("시작됐거나 완료된 검사는 예약 연결을 바꿀 수 없다")
    void updateReservationAfterStart(ExaminationStatus status) {
        Examination examination = examinationIn(status);

        assertErrorCode(() -> examinationService.updateReservation(examination.getReferralId(), UUID.randomUUID(),
                UUID.randomUUID()), ErrorCode.EXAMINATION_RESERVATION_NOT_CHANGEABLE);
        assertThat(reload(examination.getId()).getReservationId()).isEqualTo(examination.getReservationId());
    }

    @Test
    @DisplayName("검사가 없는 의뢰의 예약 연결 변경은 EXAMINATION_NOT_FOUND")
    void updateReservationNotFound() {
        assertErrorCode(() -> examinationService.updateReservation(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID()), ErrorCode.EXAMINATION_NOT_FOUND);
    }

    // ===== 의뢰별 검사 조회 =====

    @Test
    @DisplayName("의뢰 ID로 검사를 조회한다")
    void getByReferralId() {
        Examination examination = examinationIn(ExaminationStatus.IN_PROGRESS);

        ExaminationResponse response = examinationService.getByReferralId(examination.getReferralId());

        assertThat(response.examinationId()).isEqualTo(examination.getId());
        assertThat(response.examInstitutionId()).isEqualTo(examination.getExamInstitutionId());
        assertThat(response.status()).isEqualTo(ExaminationStatus.IN_PROGRESS);
        assertThat(response.startedAt()).isEqualTo(examination.getStartedAt());
        assertThat(response.completedAt()).isNull();
    }

    @Test
    @DisplayName("검사가 없는 의뢰를 조회하면 EXAMINATION_NOT_FOUND")
    void getByReferralIdNotFound() {
        assertErrorCode(() -> examinationService.getByReferralId(UUID.randomUUID()), ErrorCode.EXAMINATION_NOT_FOUND);
    }

    // ===== 검사 상태 변경 =====

    @Test
    @DisplayName("SCHEDULED → IN_PROGRESS는 의뢰·예약 검증 연동 전까지 EXAMINATION_START_NOT_ALLOWED로 막힌다")
    void startIsBlockedUntilIntegration() {
        Examination examination = examinationIn(ExaminationStatus.SCHEDULED);

        assertErrorCode(() -> examinationService.changeStatus(examination.getId(), ExaminationStatus.IN_PROGRESS),
                ErrorCode.EXAMINATION_START_NOT_ALLOWED);

        Examination unchanged = reload(examination.getId());
        assertThat(unchanged.getStatus()).isEqualTo(ExaminationStatus.SCHEDULED);
        assertThat(unchanged.getStartedAt()).isNull();
    }

    @Test
    @DisplayName("IN_PROGRESS → COMPLETED 성공: completed_at 기록, started_at 유지")
    void complete() {
        Examination examination = examinationIn(ExaminationStatus.IN_PROGRESS);

        ExaminationStatusChangeResponse response =
                examinationService.changeStatus(examination.getId(), ExaminationStatus.COMPLETED);

        Examination completed = reload(examination.getId());
        assertThat(response.previousStatus()).isEqualTo(ExaminationStatus.IN_PROGRESS);
        assertThat(response.currentStatus()).isEqualTo(ExaminationStatus.COMPLETED);
        assertThat(response.referralId()).isEqualTo(examination.getReferralId());
        assertThat(response.updatedAt()).isEqualTo(completed.getUpdatedAt());
        assertThat(completed.getCompletedAt()).isNotNull();
        assertThat(completed.getStartedAt()).isEqualTo(examination.getStartedAt());
    }

    @ParameterizedTest
    @EnumSource(ExaminationStatus.class)
    @DisplayName("현재와 같은 상태로 요청하면 그대로 돌려주고 시각 값을 바꾸지 않는다 (멱등)")
    void sameStatusIsIdempotent(ExaminationStatus status) {
        Examination before = examinationIn(status);

        ExaminationStatusChangeResponse response = examinationService.changeStatus(before.getId(), status);

        Examination after = reload(before.getId());
        assertThat(response.previousStatus()).isEqualTo(status);
        assertThat(response.currentStatus()).isEqualTo(status);
        assertThat(response.updatedAt()).isEqualTo(before.getUpdatedAt());
        assertThat(after.getUpdatedAt()).isEqualTo(before.getUpdatedAt());
        assertThat(after.getStartedAt()).isEqualTo(before.getStartedAt());
        assertThat(after.getCompletedAt()).isEqualTo(before.getCompletedAt());
    }

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({"SCHEDULED, COMPLETED", "IN_PROGRESS, SCHEDULED", "COMPLETED, IN_PROGRESS", "COMPLETED, SCHEDULED"})
    @DisplayName("단계 건너뛰기·되돌리기는 INVALID_EXAMINATION_STATUS_TRANSITION이고 아무것도 바뀌지 않는다")
    void invalidTransition(ExaminationStatus current, ExaminationStatus requested) {
        Examination before = examinationIn(current);

        assertErrorCode(() -> examinationService.changeStatus(before.getId(), requested),
                ErrorCode.INVALID_EXAMINATION_STATUS_TRANSITION);

        Examination after = reload(before.getId());
        assertThat(after.getStatus()).isEqualTo(current);
        assertThat(after.getUpdatedAt()).isEqualTo(before.getUpdatedAt());
    }

    @Test
    @DisplayName("존재하지 않는 검사의 상태 변경은 EXAMINATION_NOT_FOUND")
    void changeStatusNotFound() {
        assertErrorCode(() -> examinationService.changeStatus(UUID.randomUUID(), ExaminationStatus.COMPLETED),
                ErrorCode.EXAMINATION_NOT_FOUND);
    }

    // ===== 검사 결과 등록 =====

    @Test
    @DisplayName("완료된 검사에 결과를 등록하고, 검사 상태는 COMPLETED를 유지한다")
    void registerResult() {
        Examination examination = examinationIn(ExaminationStatus.COMPLETED);

        ExaminationResultCreateResponse response =
                examinationResultService.register(examination.getId(), "가상 결과 요약", "가상 추가 소견");

        var saved = examinationResultRepository.findById(response.resultId()).orElseThrow();
        assertThat(response.examinationId()).isEqualTo(examination.getId());
        assertThat(response.registeredAt()).isEqualTo(saved.getRegisteredAt());
        assertThat(saved.getSummary()).isEqualTo("가상 결과 요약");
        assertThat(saved.getNotes()).isEqualTo("가상 추가 소견");

        Examination after = reload(examination.getId());
        assertThat(after.getStatus()).isEqualTo(ExaminationStatus.COMPLETED);
        assertThat(after.getUpdatedAt()).isEqualTo(examination.getUpdatedAt());
    }

    @Test
    @DisplayName("notes 없이 결과를 등록하면 NULL로 저장된다")
    void registerResultWithoutNotes() {
        Examination examination = examinationIn(ExaminationStatus.COMPLETED);

        ExaminationResultCreateResponse response = examinationResultService.register(examination.getId(), "요약", null);

        assertThat(examinationResultRepository.findById(response.resultId()).orElseThrow().getNotes()).isNull();
    }

    @ParameterizedTest
    @EnumSource(value = ExaminationStatus.class, names = {"SCHEDULED", "IN_PROGRESS"})
    @DisplayName("완료 전 검사에 결과를 등록하면 EXAMINATION_NOT_COMPLETED")
    void registerResultBeforeCompleted(ExaminationStatus status) {
        Examination examination = examinationIn(status);

        assertErrorCode(() -> examinationResultService.register(examination.getId(), "요약", null),
                ErrorCode.EXAMINATION_NOT_COMPLETED);
        assertThat(examinationResultRepository.existsByExaminationId(examination.getId())).isFalse();
    }

    @Test
    @DisplayName("존재하지 않는 검사에 결과를 등록하면 EXAMINATION_NOT_FOUND")
    void registerResultNotFound() {
        assertErrorCode(() -> examinationResultService.register(UUID.randomUUID(), "요약", null),
                ErrorCode.EXAMINATION_NOT_FOUND);
    }

    @Test
    @DisplayName("summary가 없거나 공백이면 VALIDATION_FAILED")
    void registerResultWithoutSummary() {
        Examination examination = examinationIn(ExaminationStatus.COMPLETED);

        assertErrorCode(() -> examinationResultService.register(examination.getId(), null, null),
                ErrorCode.VALIDATION_FAILED);
        assertErrorCode(() -> examinationResultService.register(examination.getId(), "   ", null),
                ErrorCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("이미 결과가 있는 검사에 다시 등록하면 EXAMINATION_RESULT_ALREADY_EXISTS이고 기존 결과는 그대로다")
    void registerResultTwice() {
        Examination examination = examinationIn(ExaminationStatus.COMPLETED);
        ExaminationResultCreateResponse first = examinationResultService.register(examination.getId(), "첫 결과", null);

        assertErrorCode(() -> examinationResultService.register(examination.getId(), "덮어쓰기 시도", null),
                ErrorCode.EXAMINATION_RESULT_ALREADY_EXISTS);
        assertThat(examinationResultRepository.findById(first.resultId()).orElseThrow().getSummary())
                .isEqualTo("첫 결과");
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED) // 스레드마다 별도 트랜잭션으로 실제 커밋·충돌을 재현한다
    @DisplayName("같은 검사에 10건이 동시에 결과를 등록하면 1건만 성공하고 나머지는 ALREADY_EXISTS, DB에는 1건뿐이다")
    void registerResultConcurrently() throws Exception {
        Examination examination = examinationIn(ExaminationStatus.COMPLETED);
        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(1);

        try {
            List<Future<ErrorCode>> results = new ArrayList<>();
            for (int i = 0; i < threadCount; i++) {
                String summary = "동시 등록 " + i;
                results.add(executor.submit(() -> {
                    ready.await();
                    try {
                        examinationResultService.register(examination.getId(), summary, null);
                        return null;   // 성공
                    } catch (BaseException e) {
                        return e.getErrorCode();
                    }
                }));
            }
            ready.countDown();

            int successCount = 0;
            for (Future<ErrorCode> result : results) {
                ErrorCode errorCode = result.get();
                if (errorCode == null) {
                    successCount++;
                } else {
                    assertThat(errorCode).isEqualTo(ErrorCode.EXAMINATION_RESULT_ALREADY_EXISTS);
                }
            }
            assertThat(successCount).isEqualTo(1);
            assertThat(examinationResultRepository.countByExaminationId(examination.getId())).isEqualTo(1);
        } finally {
            executor.shutdown();
            examinationResultRepository.deleteAll(examinationResultRepository.findAll().stream()
                    .filter(result -> result.getExamination().getId().equals(examination.getId()))
                    .toList());
            examinationRepository.deleteById(examination.getId());
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("같은 검사에 완료 요청이 동시에 와도 실제 변경은 1번이고 모든 요청이 오류 없이 COMPLETED를 받는다")
    void completeConcurrently() throws Exception {
        Examination examination = examinationIn(ExaminationStatus.IN_PROGRESS);
        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(1);

        try {
            List<Future<ExaminationStatusChangeResponse>> results = new ArrayList<>();
            for (int i = 0; i < threadCount; i++) {
                results.add(executor.submit(() -> {
                    ready.await();
                    return examinationService.changeStatus(examination.getId(), ExaminationStatus.COMPLETED);
                }));
            }
            ready.countDown();

            int changedCount = 0;
            for (Future<ExaminationStatusChangeResponse> result : results) {
                ExaminationStatusChangeResponse response = result.get();
                assertThat(response.currentStatus()).isEqualTo(ExaminationStatus.COMPLETED);
                if (response.previousStatus() == ExaminationStatus.IN_PROGRESS) {
                    changedCount++;
                }
            }
            assertThat(changedCount).as("실제로 상태를 바꾼 요청 수").isEqualTo(1);
            Examination completed = reload(examination.getId());
            assertThat(completed.getStartedAt()).isEqualTo(examination.getStartedAt());
        } finally {
            executor.shutdown();
            examinationRepository.deleteById(examination.getId());
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("같은 의뢰의 검사 생성 요청이 동시에 들어와도 검사는 1건만 저장된다")
    void createScheduledConcurrently() throws Exception {

        // 모든 요청에 동일한 의뢰·예약·검사기관 ID를 사용한다
        UUID referralId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        UUID institutionId = UUID.randomUUID();

        int threadCount = 10;

        // 동시에 실행할 스레드 10개 생성
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        // 모든 스레드가 비슷한 시점에 요청하도록 대기시키는 장치
        CountDownLatch start = new CountDownLatch(1);

        List<Future<UUID>> results = new ArrayList<>();

        try {
            for (int i = 0; i < threadCount; i++) {
                results.add(executor.submit(() -> {

                    // 메인 스레드가 신호를 보낼 때까지 대기
                    start.await();

                    try {
                        // 동일한 의뢰의 검사 생성 요청
                        return examinationService.createScheduled(
                                referralId,
                                reservationId,
                                institutionId
                        );

                    } catch (BaseException e) {

                        // 동시에 INSERT하다 중복 충돌이 발생했다면
                        // 예상한 비즈니스 예외로 처리됐는지 검증한다
                        assertThat(e.getErrorCode())
                                .isEqualTo(ErrorCode.EXAMINATION_ALREADY_EXISTS);

                        return null;
                    }
                }));
            }

            // 대기 중인 스레드들이 요청을 시작하도록 신호 전달
            start.countDown();

            UUID returnedId = null;

            // 모든 요청의 결과 확인
            for (Future<UUID> future : results) {
                UUID id = future.get();

                if (id != null) {
                    if (returnedId == null) {
                        returnedId = id;
                    } else {
                        // 정상 반환된 검사 ID는 모두 동일해야 한다
                        assertThat(id).isEqualTo(returnedId);
                    }
                }
            }

            // 최소 하나의 요청은 정상적으로 검사 ID를 반환해야 한다
            assertThat(returnedId).isNotNull();

            // DB에는 동일 의뢰의 검사가 정확히 1건만 존재해야 한다
            assertThat(examinationRepository.countByReferralId(referralId))
                    .isEqualTo(1);

            // 저장된 검사 ID와 반환된 검사 ID가 일치하는지 확인한다
            Examination saved = examinationRepository.findByReferralId(referralId)
                    .orElseThrow();

            assertThat(saved.getId()).isEqualTo(returnedId);

        } finally {
            // 예외가 발생해도 대기 중인 스레드가 실행될 수 있도록 해제
            start.countDown();

            // 모든 작업이 끝나도록 대기한 뒤 스레드 풀 종료
            executor.shutdown();

            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }

            // 이번 테스트에서 생성한 검사 기록만 정리한다
            // 테스트 전용 DB에 대해서만 실행해야 한다
            examinationRepository.findByReferralId(referralId)
                    .ifPresent(examinationRepository::delete);
        }
    }
}
