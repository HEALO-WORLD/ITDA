package healoworld.referral_service.application.service;

import healoworld.referral_service.domain.entity.Examination;
import healoworld.referral_service.domain.entity.Referral;
import healoworld.referral_service.domain.entity.Reservation;
import healoworld.referral_service.domain.enums.ExaminationStatus;
import healoworld.referral_service.domain.enums.RoleType;
import healoworld.referral_service.global.config.ClockConfig;
import healoworld.referral_service.global.config.QuerydslConfig;
import healoworld.referral_service.global.dto.PagedResponse;
import healoworld.referral_service.global.exception.BaseException;
import healoworld.referral_service.global.exception.ErrorCode;
import healoworld.referral_service.infrastructure.repository.ExaminationRepository;
import healoworld.referral_service.infrastructure.repository.ExaminationResultRepository;
import healoworld.referral_service.infrastructure.repository.ReferralRepository;
import healoworld.referral_service.infrastructure.repository.ReservationRepository;
import healoworld.referral_service.presentation.dto.response.ExaminationListResponse;
import healoworld.referral_service.presentation.dto.response.ExaminationResponse;
import healoworld.referral_service.presentation.dto.response.ExaminationResultCreateResponse;
import healoworld.referral_service.presentation.dto.response.ExaminationStatusChangeResponse;

import healoworld.referral_service.support.PostgresContainerSupport;
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
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
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

/**
 * ExaminationService 통합 테스트
 * <p>
 * - 실제 PostgreSQL을 사용하여 검사 생성·조회·상태 변경을 검증한다.
 * - 의뢰·예약·검사 간 연결 관계 및 검사 시작 조건을 검증한다.
 * - 검사 결과 등록과 중복 요청 처리의 정합성을 확인한다.
 * - 기본 테스트는 트랜잭션 종료 시 롤백된다.
 * - 동시성 테스트는 별도 트랜잭션으로 실행하고 테스트 데이터를 정리한다.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
        ExaminationService.class,
        ExaminationQueryService.class,
        ExaminationResultService.class,
        QuerydslConfig.class, ClockConfig.class
})
class ExaminationServiceIntegrationTest extends PostgresContainerSupport {

    @Autowired
    private ExaminationService examinationService;

    @Autowired
    private ExaminationQueryService examinationQueryService;

    @Autowired
    private ExaminationResultService examinationResultService;

    @Autowired
    private ExaminationRepository examinationRepository;

    @Autowired
    private ExaminationResultRepository examinationResultRepository;

    @Autowired
    private ReferralRepository referralRepository;

    @Autowired
    private ReservationRepository reservationRepository;

    // =========================================================
    // 공통 테스트 메서드
    // =========================================================

    private static void assertErrorCode(
            ThrowingCallable call,
            ErrorCode errorCode
    ) {
        assertThatThrownBy(call)
                .isInstanceOf(BaseException.class)
                .extracting("errorCode")
                .isEqualTo(errorCode);
    }

    private Examination reload(UUID examinationId) {
        return examinationRepository.findById(examinationId)
                .orElseThrow();
    }

    private ExaminationStatusChangeResponse changeStatusAsExaminer(
            UUID examinationId,
            ExaminationStatus status
    ) {
        Examination examination = reload(examinationId);

        return examinationService.changeStatus(
                examinationId,
                examination.getExamInstitutionId(),
                RoleType.EXAMINER,
                status
        );
    }

    private LocalDateTime now() {
        return LocalDateTime.now()
                .truncatedTo(ChronoUnit.MICROS);
    }

    /**
     * 상태 변경 및 결과 등록 테스트에 사용할 검사 데이터를 생성한다.
     * <p>
     * 검사 시작 조건 자체를 검증하는 테스트가 아니므로
     * 필요한 상태는 Repository의 조건부 UPDATE를 통해 준비한다.
     */
    private Examination examinationIn(ExaminationStatus status) {

        UUID reservationId = UUID.randomUUID();

        UUID examinationId = examinationService.createScheduled(
                UUID.randomUUID(),
                reservationId,
                UUID.randomUUID()
        );

        if (status != ExaminationStatus.SCHEDULED) {
            examinationRepository.startIfScheduled(
                    examinationId,
                    reservationId,
                    now()
            );
        }

        if (status == ExaminationStatus.COMPLETED) {
            examinationRepository.completeIfInProgress(
                    examinationId,
                    now()
            );
        }

        return reload(examinationId);
    }

    /**
     * 검사 시작 조건 검증을 위한 의뢰·예약·검사 데이터를 생성한다.
     *
     * @param confirmReferral    의뢰 확정 여부
     * @param confirmReservation 예약 확정 여부
     * @param sameReservation    의뢰의 현재 예약과 검사 예약의 일치 여부
     */
    private UUID createExaminationForStartTest(
            boolean confirmReferral,
            boolean confirmReservation,
            boolean sameReservation
    ) {
        return createExaminationForStartTest(
                confirmReferral,
                confirmReservation,
                sameReservation,
                true
        );
    }

    private UUID createExaminationForStartTest(
            boolean confirmReferral,
            boolean confirmReservation,
            boolean sameReservation,
            boolean sameInstitution
    ) {

        UUID examInstitutionId = UUID.randomUUID();

        // 1. 의뢰 생성 (PENDING)
        Referral referral = referralRepository.saveAndFlush(
                Referral.create(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "테스트 환자",
                        UUID.randomUUID()
                )
        );

        // 2. 예약 생성 (REQUESTED)
        LocalDateTime examDate = LocalDateTime.of(
                2026, 11, 3, 10, 0
        );

        Reservation reservation = Reservation.create(
                referral,
                1,
                UUID.randomUUID(),
                examInstitutionId,
                UUID.randomUUID(),
                examDate,
                examDate,
                examDate.plusMinutes(30)
        );

        // 3. 예약 HOLD
        reservation.hold();

        // 4. 예약 확정 여부 선택
        if (confirmReservation) {
            reservation.confirm();
        }

        reservationRepository.saveAndFlush(reservation);

        // 5. 의뢰의 현재 예약 일치 여부 선택
        UUID currentReservationId = sameReservation
                ? reservation.getId()
                : UUID.randomUUID();

        referral.assignReservation(currentReservationId);

        // 6. 의뢰 확정 여부 선택
        if (confirmReferral) {
            referral.confirm();
        }

        referralRepository.saveAndFlush(referral);

        // 7. 검사 생성 (SCHEDULED)
        UUID examinationInstitutionId = sameInstitution
                ? examInstitutionId
                : UUID.randomUUID();

        return examinationService.createScheduled(
                referral.getId(),
                reservation.getId(),
                examinationInstitutionId
        );
    }

    // =========================================================
    // 1. 검사 생성
    // =========================================================

    @Test
    @DisplayName("최초 예약 확정 시 SCHEDULED 검사가 생성된다")
    void createScheduled() {

        UUID referralId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        UUID institutionId = UUID.randomUUID();

        UUID examinationId = examinationService.createScheduled(
                referralId,
                reservationId,
                institutionId
        );

        Examination created = reload(examinationId);

        assertThat(created.getReferralId())
                .isEqualTo(referralId);

        assertThat(created.getReservationId())
                .isEqualTo(reservationId);

        assertThat(created.getExamInstitutionId())
                .isEqualTo(institutionId);

        assertThat(created.getStatus())
                .isEqualTo(ExaminationStatus.SCHEDULED);
    }

    @Test
    @DisplayName("같은 확정 처리가 다시 실행되면 기존 검사 ID를 반환한다")
    void createScheduledIsIdempotent() {

        UUID referralId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        UUID institutionId = UUID.randomUUID();

        UUID first = examinationService.createScheduled(
                referralId,
                reservationId,
                institutionId
        );

        UUID second = examinationService.createScheduled(
                referralId,
                reservationId,
                institutionId
        );

        assertThat(second).isEqualTo(first);

        assertThat(examinationRepository.findByReferralId(referralId))
                .isPresent();
    }

    @Test
    @DisplayName("다른 예약으로 이미 검사가 존재하면 중복 생성할 수 없다")
    void createScheduledWithOtherReservation() {

        UUID referralId = UUID.randomUUID();
        UUID institutionId = UUID.randomUUID();

        examinationService.createScheduled(
                referralId,
                UUID.randomUUID(),
                institutionId
        );

        assertErrorCode(
                () -> examinationService.createScheduled(
                        referralId,
                        UUID.randomUUID(),
                        institutionId
                ),
                ErrorCode.EXAMINATION_ALREADY_EXISTS
        );
    }

    @Test
    @DisplayName("최초 예약 확정 시 검사 1건 생성 및 중복 생성 방지")
    void syncScheduledExaminationCreatesOnce() {
        // given
        UUID referralId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        UUID institutionId = UUID.randomUUID();

        // when
        UUID firstId = examinationService.syncScheduledExamination(
                referralId, reservationId, institutionId
        );

        // 동일한 요청 재실행
        UUID secondId = examinationService.syncScheduledExamination(
                referralId, reservationId, institutionId
        );

        // then
        Examination examination = examinationRepository
                .findById(firstId)
                .orElseThrow();

        assertThat(firstId).isEqualTo(secondId);
        assertThat(examination.getStatus())
                .isEqualTo(ExaminationStatus.SCHEDULED);
        assertThat(examination.getReservationId())
                .isEqualTo(reservationId);
        assertThat(examinationRepository.countByReferralId(referralId))
                .isEqualTo(1);
    }

    // =========================================================
    // 2. 재예약 연결 변경
    // =========================================================

    @Test
    @DisplayName("재예약 시 검사 ID를 유지하고 예약·검사기관 정보만 변경한다")
    void updateReservation() {

        UUID referralId = UUID.randomUUID();

        UUID examinationId = examinationService.createScheduled(
                referralId,
                UUID.randomUUID(),
                UUID.randomUUID()
        );

        UUID reservationB = UUID.randomUUID();
        UUID institutionB = UUID.randomUUID();

        UUID result = examinationService.updateReservation(
                referralId,
                reservationB,
                institutionB
        );

        Examination relinked = reload(examinationId);

        assertThat(result).isEqualTo(examinationId);

        assertThat(relinked.getReferralId())
                .isEqualTo(referralId);

        assertThat(relinked.getReservationId())
                .isEqualTo(reservationB);

        assertThat(relinked.getExamInstitutionId())
                .isEqualTo(institutionB);

        assertThat(relinked.getStatus())
                .isEqualTo(ExaminationStatus.SCHEDULED);
    }

    @Test
    @DisplayName("동일한 예약으로 다시 연결하면 수정 시각이 변경되지 않는다")
    void updateReservationIsIdempotent() {

        UUID referralId = UUID.randomUUID();
        UUID reservationB = UUID.randomUUID();
        UUID institutionB = UUID.randomUUID();

        UUID examinationId = examinationService.createScheduled(
                referralId,
                UUID.randomUUID(),
                UUID.randomUUID()
        );

        examinationService.updateReservation(
                referralId,
                reservationB,
                institutionB
        );

        LocalDateTime updatedAt = reload(examinationId)
                .getUpdatedAt();

        examinationService.updateReservation(
                referralId,
                reservationB,
                institutionB
        );

        assertThat(reload(examinationId).getUpdatedAt())
                .isEqualTo(updatedAt);
    }

    @ParameterizedTest
    @EnumSource(
            value = ExaminationStatus.class,
            names = {"IN_PROGRESS", "COMPLETED"}
    )
    @DisplayName("시작되거나 완료된 검사는 예약 연결을 변경할 수 없다")
    void updateReservationAfterStart(ExaminationStatus status) {

        Examination examination = examinationIn(status);

        assertErrorCode(
                () -> examinationService.updateReservation(
                        examination.getReferralId(),
                        UUID.randomUUID(),
                        UUID.randomUUID()
                ),
                ErrorCode.EXAMINATION_RESERVATION_NOT_CHANGEABLE
        );

        assertThat(reload(examination.getId()).getReservationId())
                .isEqualTo(examination.getReservationId());
    }

    @Test
    @DisplayName("검사가 존재하지 않는 의뢰는 예약 연결을 변경할 수 없다")
    void updateReservationNotFound() {

        assertErrorCode(
                () -> examinationService.updateReservation(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID()
                ),
                ErrorCode.EXAMINATION_NOT_FOUND
        );
    }

    @Test
    @DisplayName("재예약 확정 시 기존 검사 기록을 새 예약에 연결")
    void syncScheduledExaminationUpdatesReservation() {
        // given
        UUID firstInstitutionId = UUID.randomUUID();
        UUID newInstitutionId = UUID.randomUUID();
        UUID referralId = UUID.randomUUID();
        UUID firstReservationId = UUID.randomUUID();
        UUID newReservationId = UUID.randomUUID();
        UUID institutionId = UUID.randomUUID();

        UUID originalExaminationId =
                examinationService.syncScheduledExamination(
                        referralId,
                        firstReservationId,
                        firstInstitutionId
                );

        // when
        UUID updatedExaminationId =
                examinationService.syncScheduledExamination(
                        referralId,
                        newReservationId,
                        newInstitutionId
                );

        // then
        Examination examination = examinationRepository
                .findById(updatedExaminationId)
                .orElseThrow();

        // 검사 ID는 그대로 유지
        assertThat(updatedExaminationId)
                .isEqualTo(originalExaminationId);

        // 예약 ID만 변경
        assertThat(examination.getReservationId())
                .isEqualTo(newReservationId);

        // 검사기관 ID도 새 검사기관으로 변경됐는지 확인
        assertThat(examination.getExamInstitutionId())
                .isEqualTo(newInstitutionId);

        // 검사 중복 생성 방지
        assertThat(examinationRepository.countByReferralId(referralId))
                .isEqualTo(1);
    }

    // =========================================================
    // 3. 의뢰별 검사 조회
    // =========================================================

    @Test
    @DisplayName("의뢰 ID로 검사 정보를 조회한다")
    void getByReferralId() {

        Examination examination = examinationIn(
                ExaminationStatus.IN_PROGRESS
        );

        ExaminationResponse response =
                examinationQueryService.getByReferralId(
                        examination.getReferralId()
                );

        assertThat(response.examinationId())
                .isEqualTo(examination.getId());

        assertThat(response.examInstitutionId())
                .isEqualTo(examination.getExamInstitutionId());

        assertThat(response.status())
                .isEqualTo(ExaminationStatus.IN_PROGRESS);

        assertThat(response.startedAt())
                .isEqualTo(examination.getStartedAt());

        assertThat(response.completedAt())
                .isNull();
    }

    @Test
    @DisplayName("검사가 존재하지 않는 의뢰를 조회하면 오류가 발생한다")
    void getByReferralIdNotFound() {

        assertErrorCode(
                () -> examinationQueryService.getByReferralId(
                        UUID.randomUUID()
                ),
                ErrorCode.EXAMINATION_NOT_FOUND
        );
    }

    @Test
    @DisplayName("예약 취소 후 의뢰가 삭제되어도 검사기관 목록은 오류 없이 조회되고 해당 검사는 제외된다")
    void getExaminationsAfterCanceledReferralDeleted() {

        // given: 확정된 의뢰·예약의 SCHEDULED 검사 → 예약·의뢰 취소 → 의뢰 soft delete
        Examination examination = reload(createExaminationForStartTest(true, true, true));

        Referral referral = referralRepository.findById(examination.getReferralId()).orElseThrow();
        Reservation reservation = reservationRepository.findById(examination.getReservationId()).orElseThrow();
        reservation.cancel();
        referral.cancel();
        referral.delete(UUID.randomUUID());
        reservationRepository.saveAndFlush(reservation);
        referralRepository.saveAndFlush(referral);

        // when
        PagedResponse<ExaminationListResponse> response =
                examinationQueryService.getExaminations(
                        examination.getExamInstitutionId(),
                        RoleType.EXAMINER,
                        PageRequest.of(0, 10, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")))
                );

        // then: 검사 행은 SCHEDULED로 남아 있지만 목록·전체 건수에는 나오지 않는다
        assertThat(response.contents()).isEmpty();
        assertThat(response.totalElements()).isZero();
        assertThat(reload(examination.getId()).getStatus()).isEqualTo(ExaminationStatus.SCHEDULED);
    }

    // =========================================================
    // 4. 검사 시작 조건 검증 (E-08)
    // =========================================================

    @Test
    @DisplayName("의뢰와 예약이 확정되고 연결 정보가 일치하면 검사를 시작한다")
    void startWhenReferralAndReservationConfirmed() {

        // given
        UUID examinationId = createExaminationForStartTest(
                true,
                true,
                true
        );

        // when
        ExaminationStatusChangeResponse response =
                changeStatusAsExaminer(
                        examinationId,
                        ExaminationStatus.IN_PROGRESS
                );

        // then
        Examination updated = reload(examinationId);

        assertThat(response.previousStatus())
                .isEqualTo(ExaminationStatus.SCHEDULED);

        assertThat(response.currentStatus())
                .isEqualTo(ExaminationStatus.IN_PROGRESS);

        assertThat(updated.getStatus())
                .isEqualTo(ExaminationStatus.IN_PROGRESS);

        assertThat(updated.getStartedAt())
                .isNotNull();

        assertThat(updated.getCompletedAt())
                .isNull();
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "의뢰가 PENDING 상태, false, true, true",
            "예약이 HELD 상태, true, false, true",
            "의뢰의 현재 예약이 다름, true, true, false"
    })
    @DisplayName("검사 시작 조건을 만족하지 않으면 시작할 수 없다")
    void rejectInvalidExaminationStart(
            String scenario,
            boolean confirmReferral,
            boolean confirmReservation,
            boolean sameReservation
    ) {

        // given
        UUID examinationId = createExaminationForStartTest(
                confirmReferral,
                confirmReservation,
                sameReservation
        );

        // when & then
        assertErrorCode(
                () -> changeStatusAsExaminer(
                        examinationId,
                        ExaminationStatus.IN_PROGRESS
                ),
                ErrorCode.EXAMINATION_START_NOT_ALLOWED
        );

        // 검사 상태가 변경되지 않았는지 확인
        Examination examination = reload(examinationId);

        assertThat(examination.getStatus())
                .isEqualTo(ExaminationStatus.SCHEDULED);

        assertThat(examination.getStartedAt())
                .isNull();
    }

    @Test
    @DisplayName("연결된 의뢰가 존재하지 않으면 검사를 시작할 수 없다")
    void startBlockedWhenReferralNotFound() {

        Examination examination = examinationIn(
                ExaminationStatus.SCHEDULED
        );

        assertErrorCode(
                () -> changeStatusAsExaminer(
                        examination.getId(),
                        ExaminationStatus.IN_PROGRESS
                ),
                ErrorCode.EXAMINATION_START_NOT_ALLOWED
        );

        Examination unchanged = reload(examination.getId());

        assertThat(unchanged.getStatus())
                .isEqualTo(ExaminationStatus.SCHEDULED);

        assertThat(unchanged.getStartedAt())
                .isNull();
    }

    @Test
    @DisplayName("동일한 검사 시작 요청이 반복되어도 시작 시각은 변경되지 않는다")
    void startExaminationIdempotently() {

        // given
        UUID examinationId = createExaminationForStartTest(
                true,
                true,
                true
        );

        // 첫 번째 검사 시작
        changeStatusAsExaminer(
                examinationId,
                ExaminationStatus.IN_PROGRESS
        );

        Examination first = reload(examinationId);

        LocalDateTime startedAt = first.getStartedAt();
        LocalDateTime updatedAt = first.getUpdatedAt();

        // when: 같은 요청 재실행
        ExaminationStatusChangeResponse response =
                changeStatusAsExaminer(
                        examinationId,
                        ExaminationStatus.IN_PROGRESS
                );

        // then
        Examination second = reload(examinationId);

        assertThat(response.previousStatus())
                .isEqualTo(ExaminationStatus.IN_PROGRESS);

        assertThat(response.currentStatus())
                .isEqualTo(ExaminationStatus.IN_PROGRESS);

        assertThat(second.getStartedAt())
                .isEqualTo(startedAt);

        assertThat(second.getUpdatedAt())
                .isEqualTo(updatedAt);
    }

    @Test
    @DisplayName("예약과 검사의 검사기관이 다르면 검사 시작을 거부한다")
    void rejectStartWhenExamInstitutionMismatch() {

        // given: 의뢰·예약은 확정됐지만 검사기관 ID가 다름
        UUID examinationId = createExaminationForStartTest(
                true,
                true,
                true,
                false
        );

        // when & then: 검사 시작 거부
        assertErrorCode(
                () -> changeStatusAsExaminer(
                        examinationId,
                        ExaminationStatus.IN_PROGRESS
                ),
                ErrorCode.EXAMINATION_START_NOT_ALLOWED
        );

        // 상태가 변경되지 않았는지 확인
        Examination examination = reload(examinationId);

        assertThat(examination.getStatus())
                .isEqualTo(ExaminationStatus.SCHEDULED);

        assertThat(examination.getStartedAt())
                .isNull();
    }

    // =========================================================
    // 5. 검사 완료 및 상태 변경
    // =========================================================

    @Test
    @DisplayName("IN_PROGRESS → COMPLETED 변경 시 완료 시각을 기록한다")
    void complete() {

        Examination examination = examinationIn(
                ExaminationStatus.IN_PROGRESS
        );

        ExaminationStatusChangeResponse response =
                changeStatusAsExaminer(
                        examination.getId(),
                        ExaminationStatus.COMPLETED
                );

        Examination completed = reload(examination.getId());

        assertThat(response.previousStatus())
                .isEqualTo(ExaminationStatus.IN_PROGRESS);

        assertThat(response.currentStatus())
                .isEqualTo(ExaminationStatus.COMPLETED);

        assertThat(response.referralId())
                .isEqualTo(examination.getReferralId());

        assertThat(response.updatedAt())
                .isEqualTo(completed.getUpdatedAt());

        assertThat(completed.getCompletedAt())
                .isNotNull();

        assertThat(completed.getStartedAt())
                .isEqualTo(examination.getStartedAt());
    }

    @ParameterizedTest
    @EnumSource(ExaminationStatus.class)
    @DisplayName("같은 상태로 변경 요청하면 상태와 시각이 유지된다")
    void sameStatusIsIdempotent(ExaminationStatus status) {

        Examination before = examinationIn(status);

        ExaminationStatusChangeResponse response =
                changeStatusAsExaminer(
                        before.getId(),
                        status
                );

        Examination after = reload(before.getId());

        assertThat(response.previousStatus())
                .isEqualTo(status);

        assertThat(response.currentStatus())
                .isEqualTo(status);

        assertThat(response.updatedAt())
                .isEqualTo(before.getUpdatedAt());

        assertThat(after.getUpdatedAt())
                .isEqualTo(before.getUpdatedAt());

        assertThat(after.getStartedAt())
                .isEqualTo(before.getStartedAt());

        assertThat(after.getCompletedAt())
                .isEqualTo(before.getCompletedAt());
    }

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({
            "SCHEDULED, COMPLETED",
            "IN_PROGRESS, SCHEDULED",
            "COMPLETED, IN_PROGRESS",
            "COMPLETED, SCHEDULED"
    })
    @DisplayName("허용되지 않은 검사 상태 변경은 거부된다")
    void invalidTransition(
            ExaminationStatus current,
            ExaminationStatus requested
    ) {

        Examination before = examinationIn(current);

        assertErrorCode(
                () -> changeStatusAsExaminer(
                        before.getId(),
                        requested
                ),
                ErrorCode.INVALID_EXAMINATION_STATUS_TRANSITION
        );

        Examination after = reload(before.getId());

        assertThat(after.getStatus())
                .isEqualTo(current);

        assertThat(after.getUpdatedAt())
                .isEqualTo(before.getUpdatedAt());
    }

    @Test
    @DisplayName("존재하지 않는 검사의 상태를 변경하면 오류가 발생한다")
    void changeStatusNotFound() {

        assertErrorCode(
                () -> examinationService.changeStatus(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        RoleType.EXAMINER,
                        ExaminationStatus.COMPLETED
                ),
                ErrorCode.EXAMINATION_NOT_FOUND
        );
    }

    @Test
    @DisplayName("다른 검사기관의 EXAMINER는 검사 상태를 변경할 수 없다")
    void changeStatusDeniedForOtherInstitution() {

        // given
        Examination examination = examinationIn(ExaminationStatus.SCHEDULED);

        UUID otherInstitutionId = UUID.randomUUID();

        // when & then
        assertErrorCode(
                () -> examinationService.changeStatus(
                        examination.getId(),
                        otherInstitutionId,
                        RoleType.EXAMINER,
                        ExaminationStatus.IN_PROGRESS
                ),
                ErrorCode.EXAMINATION_ACCESS_DENIED
        );

        // 검사 상태가 변경되지 않았는지 확인
        assertThat(reload(examination.getId()).getStatus())
                .isEqualTo(ExaminationStatus.SCHEDULED);
    }

    @Test
    @DisplayName("REQUESTER는 검사 상태를 변경할 수 없다")
    void changeStatusDeniedForRequester() {

        // given
        Examination examination = examinationIn(ExaminationStatus.SCHEDULED);

        // when & then
        assertErrorCode(
                () -> examinationService.changeStatus(
                        examination.getId(),
                        examination.getExamInstitutionId(),
                        RoleType.REQUESTER,
                        ExaminationStatus.IN_PROGRESS
                ),
                ErrorCode.EXAMINATION_ACCESS_DENIED
        );

        assertThat(reload(examination.getId()).getStatus())
                .isEqualTo(ExaminationStatus.SCHEDULED);
    }

    @Test
    @DisplayName("ADMIN도 검사 상태를 변경할 수 없다")
    void changeStatusDeniedForAdmin() {

        // given
        Examination examination = examinationIn(ExaminationStatus.SCHEDULED);

        // when & then
        assertErrorCode(
                () -> examinationService.changeStatus(
                        examination.getId(),
                        examination.getExamInstitutionId(),
                        RoleType.ADMIN,
                        ExaminationStatus.IN_PROGRESS
                ),
                ErrorCode.EXAMINATION_ACCESS_DENIED
        );

        assertThat(reload(examination.getId()).getStatus())
                .isEqualTo(ExaminationStatus.SCHEDULED);
    }

    // =========================================================
    // 6. 검사 결과 등록
    // =========================================================

    @Test
    @DisplayName("완료된 검사에 결과를 등록하고 검사 상태를 유지한다")
    void registerResult() {

        Examination examination = examinationIn(
                ExaminationStatus.COMPLETED
        );

        ExaminationResultCreateResponse response =
                examinationResultService.register(
                        examination.getId(),
                        examination.getExamInstitutionId(),
                        "가상 결과 요약",
                        "가상 추가 소견"
                );

        var saved = examinationResultRepository.findById(
                response.resultId()
        ).orElseThrow();

        assertThat(response.examinationId())
                .isEqualTo(examination.getId());

        assertThat(response.registeredAt())
                .isEqualTo(saved.getRegisteredAt());

        assertThat(saved.getSummary())
                .isEqualTo("가상 결과 요약");

        assertThat(saved.getNotes())
                .isEqualTo("가상 추가 소견");

        Examination after = reload(examination.getId());

        assertThat(after.getStatus())
                .isEqualTo(ExaminationStatus.COMPLETED);

        assertThat(after.getUpdatedAt())
                .isEqualTo(examination.getUpdatedAt());
    }

    @Test
    @DisplayName("notes 없이 결과를 등록하면 NULL로 저장된다")
    void registerResultWithoutNotes() {

        Examination examination = examinationIn(
                ExaminationStatus.COMPLETED
        );

        ExaminationResultCreateResponse response =
                examinationResultService.register(
                        examination.getId(),
                        examination.getExamInstitutionId(),
                        "요약",
                        null
                );

        assertThat(
                examinationResultRepository
                        .findById(response.resultId())
                        .orElseThrow()
                        .getNotes()
        ).isNull();
    }

    @ParameterizedTest
    @EnumSource(
            value = ExaminationStatus.class,
            names = {"SCHEDULED", "IN_PROGRESS"}
    )
    @DisplayName("검사 완료 전에는 결과를 등록할 수 없다")
    void registerResultBeforeCompleted(ExaminationStatus status) {

        Examination examination = examinationIn(status);

        assertErrorCode(
                () -> examinationResultService.register(
                        examination.getId(),
                        examination.getExamInstitutionId(),
                        "요약",
                        null
                ),
                ErrorCode.EXAMINATION_NOT_COMPLETED
        );

        assertThat(
                examinationResultRepository.existsByExaminationId(
                        examination.getId()
                )
        ).isFalse();
    }

    @Test
    @DisplayName("존재하지 않는 검사에는 결과를 등록할 수 없다")
    void registerResultNotFound() {

        assertErrorCode(
                () -> examinationResultService.register(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "요약",
                        null
                ),
                ErrorCode.EXAMINATION_NOT_FOUND
        );
    }

    @Test
    @DisplayName("검사를 수행하는 검사기관이 아닌 기관은 결과를 등록할 수 없고 결과가 저장되지 않는다")
    void registerResultByOtherInstitution() {

        Examination examination = examinationIn(
                ExaminationStatus.COMPLETED
        );

        assertErrorCode(
                () -> examinationResultService.register(
                        examination.getId(),
                        UUID.randomUUID(),
                        "요약",
                        null
                ),
                ErrorCode.EXAMINATION_RESULT_ACCESS_DENIED
        );

        assertThat(
                examinationResultRepository.existsByExaminationId(
                        examination.getId()
                )
        ).isFalse();
    }

    @Test
    @DisplayName("결과 요약이 없거나 공백이면 등록을 거부한다")
    void registerResultWithoutSummary() {

        Examination examination = examinationIn(
                ExaminationStatus.COMPLETED
        );

        assertErrorCode(
                () -> examinationResultService.register(
                        examination.getId(),
                        examination.getExamInstitutionId(),
                        null,
                        null
                ),
                ErrorCode.VALIDATION_FAILED
        );

        assertErrorCode(
                () -> examinationResultService.register(
                        examination.getId(),
                        examination.getExamInstitutionId(),
                        "   ",
                        null
                ),
                ErrorCode.VALIDATION_FAILED
        );
    }

    @Test
    @DisplayName("이미 결과가 존재하면 중복 등록을 거부하고 기존 결과를 유지한다")
    void registerResultTwice() {

        Examination examination = examinationIn(
                ExaminationStatus.COMPLETED
        );

        ExaminationResultCreateResponse first =
                examinationResultService.register(
                        examination.getId(),
                        examination.getExamInstitutionId(),
                        "첫 결과",
                        null
                );

        assertErrorCode(
                () -> examinationResultService.register(
                        examination.getId(),
                        examination.getExamInstitutionId(),
                        "덮어쓰기 시도",
                        null
                ),
                ErrorCode.EXAMINATION_RESULT_ALREADY_EXISTS
        );

        assertThat(
                examinationResultRepository
                        .findById(first.resultId())
                        .orElseThrow()
                        .getSummary()
        ).isEqualTo("첫 결과");
    }

    // =========================================================
    // 7. 동시성 테스트
    // =========================================================

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("동시에 10건의 결과를 등록해도 1건만 저장된다")
    void registerResultConcurrently() throws Exception {

        Examination examination = examinationIn(
                ExaminationStatus.COMPLETED
        );

        int threadCount = 10;

        ExecutorService executor =
                Executors.newFixedThreadPool(threadCount);

        CountDownLatch ready = new CountDownLatch(1);

        try {
            List<Future<ErrorCode>> results = new ArrayList<>();

            for (int i = 0; i < threadCount; i++) {

                String summary = "동시 등록 " + i;

                results.add(executor.submit(() -> {

                    ready.await();

                    try {
                        examinationResultService.register(
                                examination.getId(),
                                examination.getExamInstitutionId(),
                                summary,
                                null
                        );

                        return null;

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
                    assertThat(errorCode)
                            .isEqualTo(
                                    ErrorCode.EXAMINATION_RESULT_ALREADY_EXISTS
                            );
                }
            }

            assertThat(successCount).isEqualTo(1);

            assertThat(
                    examinationResultRepository.countByExaminationId(
                            examination.getId()
                    )
            ).isEqualTo(1);

        } finally {
            executor.shutdown();

            examinationResultRepository.deleteAll(
                    examinationResultRepository.findAll()
                            .stream()
                            .filter(result ->
                                    result.getExamination()
                                            .getId()
                                            .equals(examination.getId())
                            )
                            .toList()
            );

            examinationRepository.deleteById(examination.getId());
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("동시 완료 요청은 한 번만 상태를 변경한다")
    void completeConcurrently() throws Exception {

        Examination examination = examinationIn(
                ExaminationStatus.IN_PROGRESS
        );

        int threadCount = 10;

        ExecutorService executor =
                Executors.newFixedThreadPool(threadCount);

        CountDownLatch ready = new CountDownLatch(1);

        try {
            List<Future<ExaminationStatusChangeResponse>> results =
                    new ArrayList<>();

            for (int i = 0; i < threadCount; i++) {

                results.add(executor.submit(() -> {

                    ready.await();

                    return changeStatusAsExaminer(
                            examination.getId(),
                            ExaminationStatus.COMPLETED
                    );
                }));
            }

            ready.countDown();

            int changedCount = 0;

            for (Future<ExaminationStatusChangeResponse> result : results) {

                ExaminationStatusChangeResponse response =
                        result.get();

                assertThat(response.currentStatus())
                        .isEqualTo(ExaminationStatus.COMPLETED);

                if (response.previousStatus()
                        == ExaminationStatus.IN_PROGRESS) {
                    changedCount++;
                }
            }

            assertThat(changedCount)
                    .as("실제로 상태를 바꾼 요청 수")
                    .isEqualTo(1);

            Examination completed = reload(examination.getId());

            assertThat(completed.getStartedAt())
                    .isEqualTo(examination.getStartedAt());

        } finally {
            executor.shutdown();

            examinationRepository.deleteById(examination.getId());
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("동일 의뢰의 동시 검사 생성 요청은 검사 1건만 저장한다")
    void createScheduledConcurrently() throws Exception {

        UUID referralId = UUID.randomUUID();
        UUID reservationId = UUID.randomUUID();
        UUID institutionId = UUID.randomUUID();

        int threadCount = 10;

        ExecutorService executor =
                Executors.newFixedThreadPool(threadCount);

        CountDownLatch start = new CountDownLatch(1);

        List<Future<UUID>> results = new ArrayList<>();

        try {

            for (int i = 0; i < threadCount; i++) {

                results.add(executor.submit(() -> {

                    start.await();

                    try {
                        return examinationService.createScheduled(
                                referralId,
                                reservationId,
                                institutionId
                        );

                    } catch (BaseException e) {

                        assertThat(e.getErrorCode())
                                .isEqualTo(
                                        ErrorCode.EXAMINATION_ALREADY_EXISTS
                                );

                        return null;
                    }
                }));
            }

            start.countDown();

            UUID returnedId = null;

            for (Future<UUID> future : results) {

                UUID id = future.get();

                if (id != null) {

                    if (returnedId == null) {
                        returnedId = id;

                    } else {
                        assertThat(id).isEqualTo(returnedId);
                    }
                }
            }

            assertThat(returnedId).isNotNull();

            assertThat(
                    examinationRepository.countByReferralId(referralId)
            ).isEqualTo(1);

            Examination saved =
                    examinationRepository.findByReferralId(referralId)
                            .orElseThrow();

            assertThat(saved.getId()).isEqualTo(returnedId);

        } finally {
            start.countDown();

            executor.shutdown();

            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }

            examinationRepository.findByReferralId(referralId)
                    .ifPresent(examinationRepository::delete);
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("동시에 검사 시작 요청이 10건 들어와도 상태 변경은 한 번만 수행된다")
    void startConcurrently() throws Exception {

        // 1. 정상적으로 시작 가능한 검사 생성
        UUID examinationId = createExaminationForStartTest(
                true,
                true,
                true
        );

        Examination examination = reload(examinationId);

        UUID referralId = examination.getReferralId();
        UUID reservationId = examination.getReservationId();

        int threadCount = 10;

        ExecutorService executor =
                Executors.newFixedThreadPool(threadCount);

        CountDownLatch start = new CountDownLatch(1);

        try {
            List<Future<ExaminationStatusChangeResponse>> results =
                    new ArrayList<>();

            // 2. 검사 시작 요청 10건 생성
            for (int i = 0; i < threadCount; i++) {

                results.add(executor.submit(() -> {

                    start.await();

                    return changeStatusAsExaminer(
                            examinationId,
                            ExaminationStatus.IN_PROGRESS
                    );
                }));
            }

            // 3. 동시에 요청 시작
            start.countDown();

            int changedCount = 0;

            // 4. 모든 요청 결과 확인
            for (Future<ExaminationStatusChangeResponse> future : results) {

                ExaminationStatusChangeResponse response =
                        future.get(30, TimeUnit.SECONDS);

                assertThat(response.currentStatus())
                        .isEqualTo(ExaminationStatus.IN_PROGRESS);

                // 실제 상태를 변경한 요청 수 확인
                if (response.previousStatus()
                        == ExaminationStatus.SCHEDULED) {
                    changedCount++;
                }
            }

            // 5. 상태 변경은 한 번만 발생해야 함
            assertThat(changedCount)
                    .as("실제 검사 시작 상태 변경 횟수")
                    .isEqualTo(1);

            // 6. 최종 DB 상태 확인
            Examination started = reload(examinationId);

            assertThat(started.getStatus())
                    .isEqualTo(ExaminationStatus.IN_PROGRESS);

            assertThat(started.getStartedAt())
                    .isNotNull();

        } finally {

            start.countDown();

            // 7. 스레드 종료
            executor.shutdown();

            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }

            // 8. 테스트 데이터 정리
            examinationRepository.findById(examinationId)
                    .ifPresent(examinationRepository::delete);

            reservationRepository.findById(reservationId)
                    .ifPresent(reservationRepository::delete);

            referralRepository.findById(referralId)
                    .ifPresent(referralRepository::delete);
        }
    }
}