package healoworld.referral_service.application.service;

import healoworld.referral_service.domain.entity.Examination;
import healoworld.referral_service.domain.entity.ExaminationResult;
import healoworld.referral_service.domain.entity.Referral;
import healoworld.referral_service.domain.enums.ExaminationStatus;
import healoworld.referral_service.domain.enums.Role;
import healoworld.referral_service.global.exception.BaseException;
import healoworld.referral_service.global.exception.ErrorCode;
import healoworld.referral_service.infrastructure.repository.ExaminationRepository;
import healoworld.referral_service.infrastructure.repository.ExaminationResultRepository;
import healoworld.referral_service.infrastructure.repository.ReferralRepository;
import healoworld.referral_service.presentation.dto.response.ExaminationResultResponse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

// 검사 결과 등록 시 예외 처리와
// 의뢰기관·검사기관별 결과 조회 권한 및 조회 실패 상황을 검증한다.
@ExtendWith(MockitoExtension.class)
class ExaminationResultServiceTest {

    @Mock
    private ExaminationRepository examinationRepository;

    @Mock
    private ExaminationResultRepository examinationResultRepository;

    @Mock
    private ReferralRepository referralRepository;

    @InjectMocks
    private ExaminationResultService examinationResultService;

    private final UUID examInstitutionId = UUID.randomUUID();

    // =========================================================
    // 테스트 데이터 생성 헬퍼
    // =========================================================

    private Referral createReferral(
            UUID referralId,
            UUID referringInstitutionId
    ) {
        Referral referral = Referral.create(
                referringInstitutionId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                "테스트 환자",
                UUID.randomUUID()
        );

        ReflectionTestUtils.setField(
                referral,
                "id",
                referralId
        );

        return referral;
    }

    private Examination createExamination(
            UUID examinationId,
            UUID referralId,
            UUID institutionId
    ) {
        Examination examination = Examination.schedule(
                referralId,
                UUID.randomUUID(),
                institutionId
        );

        ReflectionTestUtils.setField(
                examination,
                "id",
                examinationId
        );

        return examination;
    }

    private Examination createExamination(
            UUID examinationId,
            UUID referralId,
            UUID institutionId,
            ExaminationStatus status
    ) {
        Examination examination = createExamination(
                examinationId,
                referralId,
                institutionId
        );

        ReflectionTestUtils.setField(
                examination,
                "status",
                status
        );

        return examination;
    }

    // =========================================================
    // 1. 검사 결과 등록
    // =========================================================

    @Nested
    @DisplayName("검사 결과 등록")
    class RegisterResult {

        @Test
        @DisplayName("UNIQUE 제약 위반은 EXAMINATION_RESULT_ALREADY_EXISTS로 변환한다")
        void uniqueViolationBecomesConflict() {
            // given
            UUID examinationId = UUID.randomUUID();

            Examination examination = createExamination(
                    examinationId,
                    UUID.randomUUID(),
                    examInstitutionId,
                    ExaminationStatus.COMPLETED
            );

            given(examinationRepository.findById(examinationId))
                    .willReturn(Optional.of(examination));

            given(examinationResultRepository.saveAndFlush(
                    any(ExaminationResult.class)
            )).willThrow(
                    new DataIntegrityViolationException(
                            "duplicate",
                            new SQLException("duplicate key", "23505")
                    )
            );

            // when & then
            assertThatThrownBy(() ->
                    examinationResultService.register(
                            examinationId,
                            examInstitutionId,
                            "요약",
                            null
                    )
            )
                    .isInstanceOf(BaseException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.EXAMINATION_RESULT_ALREADY_EXISTS);
        }

        @Test
        @DisplayName("UNIQUE가 아닌 DB 제약 위반은 원래 예외를 유지한다")
        void otherViolationIsRethrown() {
            // given
            UUID examinationId = UUID.randomUUID();

            Examination examination = createExamination(
                    examinationId,
                    UUID.randomUUID(),
                    examInstitutionId,
                    ExaminationStatus.COMPLETED
            );

            given(examinationRepository.findById(examinationId))
                    .willReturn(Optional.of(examination));

            DataIntegrityViolationException dbException =
                    new DataIntegrityViolationException(
                            "not null",
                            new SQLException("null value", "23502")
                    );

            given(examinationResultRepository.saveAndFlush(
                    any(ExaminationResult.class)
            )).willThrow(dbException);

            // when & then
            assertThatThrownBy(() ->
                    examinationResultService.register(
                            examinationId,
                            examInstitutionId,
                            "요약",
                            null
                    )
            )
                    .isSameAs(dbException);
        }

        @Test
        @DisplayName("다른 검사기관은 결과를 등록할 수 없고 저장 로직도 실행되지 않는다")
        void otherInstitutionIsDenied() {
            // given
            UUID examinationId = UUID.randomUUID();

            Examination examination = createExamination(
                    examinationId,
                    UUID.randomUUID(),
                    examInstitutionId,
                    ExaminationStatus.IN_PROGRESS
            );

            given(examinationRepository.findById(examinationId))
                    .willReturn(Optional.of(examination));

            UUID otherInstitutionId = UUID.randomUUID();

            // when & then
            assertThatThrownBy(() ->
                    examinationResultService.register(
                            examinationId,
                            otherInstitutionId,
                            "요약",
                            null
                    )
            )
                    .isInstanceOf(BaseException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.EXAMINATION_RESULT_ACCESS_DENIED);

            then(examinationResultRepository).shouldHaveNoInteractions();
        }
    }

    // =========================================================
    // 2. 의뢰 ID 기반 검사 결과 조회
    // =========================================================

    @Nested
    @DisplayName("의뢰 ID 기반 결과 조회")
    class GetResultByReferralId {

        @Test
        @DisplayName("의뢰기관 소속 REQUESTER는 검사 결과를 조회할 수 있다")
        void requesterCanReadExaminationResult() {
            // given
            UUID referralId = UUID.randomUUID();
            UUID examinationId = UUID.randomUUID();
            UUID referringInstitutionId = UUID.randomUUID();

            Referral referral = createReferral(
                    referralId,
                    referringInstitutionId
            );

            Examination examination = createExamination(
                    examinationId,
                    referralId,
                    examInstitutionId,
                    ExaminationStatus.COMPLETED
            );

            ExaminationResult result = ExaminationResult.register(
                    examination,
                    "CT 검사 결과 정상",
                    "특이 소견 없음"
            );

            given(referralRepository.findById(referralId))
                    .willReturn(Optional.of(referral));

            given(examinationRepository.findByReferralId(referralId))
                    .willReturn(Optional.of(examination));

            given(examinationResultRepository.findByExamination_Id(examinationId))
                    .willReturn(Optional.of(result));

            // when
            ExaminationResultResponse response =
                    examinationResultService.getResultByReferralId(
                            referralId,
                            referringInstitutionId,
                            Role.REQUESTER
                    );

            // then
            assertThat(response.referralId()).isEqualTo(referralId);
            assertThat(response.examinationId()).isEqualTo(examinationId);
            assertThat(response.summary()).isEqualTo("CT 검사 결과 정상");
            assertThat(response.notes()).isEqualTo("특이 소견 없음");
            assertThat(response.registeredAt()).isNotNull();
        }

        @Test
        @DisplayName("다른 의뢰기관 소속 REQUESTER는 결과를 조회할 수 없다")
        void otherRequesterInstitutionCannotReadResult() {
            // given
            UUID referralId = UUID.randomUUID();
            UUID referringInstitutionId = UUID.randomUUID();
            UUID otherInstitutionId = UUID.randomUUID();

            Referral referral = createReferral(
                    referralId,
                    referringInstitutionId
            );

            given(referralRepository.findById(referralId))
                    .willReturn(Optional.of(referral));

            // when & then
            assertThatThrownBy(() ->
                    examinationResultService.getResultByReferralId(
                            referralId,
                            otherInstitutionId,
                            Role.REQUESTER
                    )
            )
                    .isInstanceOf(BaseException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.EXAMINATION_RESULT_READ_ACCESS_DENIED);

            then(examinationRepository).shouldHaveNoInteractions();
            then(examinationResultRepository).shouldHaveNoInteractions();
        }

        @ParameterizedTest
        @EnumSource(
                value = Role.class,
                names = {"EXAMINER", "ADMIN"}
        )
        @DisplayName("REQUESTER가 아닌 역할은 의뢰 ID 기반 결과를 조회할 수 없다")
        void nonRequesterCannotReadResult(Role role) {
            // given
            UUID referralId = UUID.randomUUID();

            // when & then
            assertThatThrownBy(() ->
                    examinationResultService.getResultByReferralId(
                            referralId,
                            UUID.randomUUID(),
                            role
                    )
            )
                    .isInstanceOf(BaseException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.EXAMINATION_RESULT_READ_ACCESS_DENIED);

            then(referralRepository).shouldHaveNoInteractions();
            then(examinationRepository).shouldHaveNoInteractions();
            then(examinationResultRepository).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("의뢰가 존재하지 않으면 REFERRAL_NOT_FOUND")
        void referralNotFound() {
            // given
            UUID referralId = UUID.randomUUID();

            given(referralRepository.findById(referralId))
                    .willReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() ->
                    examinationResultService.getResultByReferralId(
                            referralId,
                            UUID.randomUUID(),
                            Role.REQUESTER
                    )
            )
                    .isInstanceOf(BaseException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.REFERRAL_NOT_FOUND);

            then(examinationRepository).shouldHaveNoInteractions();
            then(examinationResultRepository).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("의뢰에 연결된 검사 기록이 없으면 EXAMINATION_NOT_FOUND")
        void examinationNotFound() {
            // given
            UUID referralId = UUID.randomUUID();
            UUID referringInstitutionId = UUID.randomUUID();

            Referral referral = createReferral(
                    referralId,
                    referringInstitutionId
            );

            given(referralRepository.findById(referralId))
                    .willReturn(Optional.of(referral));

            // when & then
            assertThatThrownBy(() ->
                    examinationResultService.getResultByReferralId(
                            referralId,
                            referringInstitutionId,
                            Role.REQUESTER
                    )
            )
                    .isInstanceOf(BaseException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.EXAMINATION_NOT_FOUND);

            then(examinationResultRepository).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("검사 결과가 등록되지 않았으면 EXAMINATION_RESULT_NOT_FOUND")
        void examinationResultNotFound() {
            // given
            UUID referralId = UUID.randomUUID();
            UUID examinationId = UUID.randomUUID();
            UUID referringInstitutionId = UUID.randomUUID();

            Referral referral = createReferral(
                    referralId,
                    referringInstitutionId
            );

            Examination examination = createExamination(
                    examinationId,
                    referralId,
                    examInstitutionId
            );

            given(referralRepository.findById(referralId))
                    .willReturn(Optional.of(referral));

            given(examinationRepository.findByReferralId(referralId))
                    .willReturn(Optional.of(examination));

            // when & then
            assertThatThrownBy(() ->
                    examinationResultService.getResultByReferralId(
                            referralId,
                            referringInstitutionId,
                            Role.REQUESTER
                    )
            )
                    .isInstanceOf(BaseException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.EXAMINATION_RESULT_NOT_FOUND);
        }
    }

    // =========================================================
    // 3. 검사 ID 기반 검사 결과 조회
    // =========================================================

    @Nested
    @DisplayName("검사 ID 기반 결과 조회")
    class GetResultByExaminationId {

        @Test
        @DisplayName("해당 검사기관 소속 EXAMINER는 검사 결과를 조회할 수 있다")
        void examinerCanReadResultByExaminationId() {
            // given
            UUID examinationId = UUID.randomUUID();
            UUID referralId = UUID.randomUUID();

            Examination examination = createExamination(
                    examinationId,
                    referralId,
                    examInstitutionId
            );

            ExaminationResult result = ExaminationResult.register(
                    examination,
                    "MRI 검사 결과 정상",
                    "특이 소견 없음"
            );

            given(examinationRepository.findById(examinationId))
                    .willReturn(Optional.of(examination));

            given(examinationResultRepository.findByExamination_Id(examinationId))
                    .willReturn(Optional.of(result));

            // when
            ExaminationResultResponse response =
                    examinationResultService.getResultByExaminationId(
                            examinationId,
                            examInstitutionId,
                            Role.EXAMINER
                    );

            // then
            assertThat(response.examinationId()).isEqualTo(examinationId);
            assertThat(response.referralId()).isEqualTo(referralId);
            assertThat(response.summary()).isEqualTo("MRI 검사 결과 정상");
            assertThat(response.notes()).isEqualTo("특이 소견 없음");
            assertThat(response.registeredAt()).isNotNull();

            then(referralRepository).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("다른 검사기관 소속 EXAMINER는 결과를 조회할 수 없다")
        void otherExaminerInstitutionCannotRead() {
            // given
            UUID examinationId = UUID.randomUUID();
            UUID otherInstitutionId = UUID.randomUUID();

            Examination examination = createExamination(
                    examinationId,
                    UUID.randomUUID(),
                    examInstitutionId
            );

            given(examinationRepository.findById(examinationId))
                    .willReturn(Optional.of(examination));

            // when & then
            assertThatThrownBy(() ->
                    examinationResultService.getResultByExaminationId(
                            examinationId,
                            otherInstitutionId,
                            Role.EXAMINER
                    )
            )
                    .isInstanceOf(BaseException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.EXAMINATION_RESULT_READ_ACCESS_DENIED);

            then(examinationResultRepository).shouldHaveNoInteractions();
            then(referralRepository).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("REQUESTER는 검사 ID 기반 결과를 조회할 수 없다")
        void requesterCannotRead() {
            // given
            UUID examinationId = UUID.randomUUID();

            // when & then
            assertThatThrownBy(() ->
                    examinationResultService.getResultByExaminationId(
                            examinationId,
                            UUID.randomUUID(),
                            Role.REQUESTER
                    )
            )
                    .isInstanceOf(BaseException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.EXAMINATION_RESULT_READ_ACCESS_DENIED);

            then(examinationRepository).shouldHaveNoInteractions();
            then(examinationResultRepository).shouldHaveNoInteractions();
            then(referralRepository).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("검사 기록이 없으면 EXAMINATION_NOT_FOUND")
        void examinationNotFound() {
            // given
            UUID examinationId = UUID.randomUUID();

            given(examinationRepository.findById(examinationId))
                    .willReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() ->
                    examinationResultService.getResultByExaminationId(
                            examinationId,
                            examInstitutionId,
                            Role.EXAMINER
                    )
            )
                    .isInstanceOf(BaseException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.EXAMINATION_NOT_FOUND);

            then(examinationResultRepository).shouldHaveNoInteractions();
            then(referralRepository).shouldHaveNoInteractions();
        }

        @Test
        @DisplayName("검사 결과가 등록되지 않았으면 EXAMINATION_RESULT_NOT_FOUND")
        void resultNotFound() {
            // given
            UUID examinationId = UUID.randomUUID();

            Examination examination = createExamination(
                    examinationId,
                    UUID.randomUUID(),
                    examInstitutionId
            );

            given(examinationRepository.findById(examinationId))
                    .willReturn(Optional.of(examination));

            // when & then
            assertThatThrownBy(() ->
                    examinationResultService.getResultByExaminationId(
                            examinationId,
                            examInstitutionId,
                            Role.EXAMINER
                    )
            )
                    .isInstanceOf(BaseException.class)
                    .extracting("errorCode")
                    .isEqualTo(ErrorCode.EXAMINATION_RESULT_NOT_FOUND);

            then(referralRepository).shouldHaveNoInteractions();
        }
    }
}