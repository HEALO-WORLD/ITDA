package healoworld.referral_service.application.service;

import healoworld.referral_service.domain.entity.Examination;
import healoworld.referral_service.domain.entity.ExaminationResult;
import healoworld.referral_service.domain.enums.ExaminationStatus;
import healoworld.referral_service.global.exception.BaseException;
import healoworld.referral_service.global.exception.ErrorCode;
import healoworld.referral_service.infrastructure.repository.ExaminationRepository;
import healoworld.referral_service.infrastructure.repository.ExaminationResultRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

// 사전 조회를 통과한 뒤 INSERT에서 DB 제약 위반이 난 경우의 예외 변환을 검증한다
@ExtendWith(MockitoExtension.class)
class ExaminationResultServiceTest {

    @Mock
    private ExaminationRepository examinationRepository;

    @Mock
    private ExaminationResultRepository examinationResultRepository;

    @InjectMocks
    private ExaminationResultService examinationResultService;

    private final UUID examInstitutionId = UUID.randomUUID();

    private Examination examination(UUID id, ExaminationStatus status) {
        Examination examination = Examination.schedule(UUID.randomUUID(), UUID.randomUUID(), examInstitutionId);
        ReflectionTestUtils.setField(examination, "id", id);
        ReflectionTestUtils.setField(examination, "status", status);
        given(examinationRepository.findById(id)).willReturn(Optional.of(examination));
        return examination;
    }

    private UUID completedExamination() {
        UUID id = UUID.randomUUID();
        examination(id, ExaminationStatus.COMPLETED);
        given(examinationResultRepository.existsByExaminationId(id)).willReturn(false);
        return id;
    }

    @Test
    @DisplayName("동시 요청으로 UNIQUE 제약(23505)에 걸리면 EXAMINATION_RESULT_ALREADY_EXISTS로 바꾼다")
    void uniqueViolationBecomesConflict() {
        UUID id = completedExamination();
        given(examinationResultRepository.saveAndFlush(any(ExaminationResult.class))).willThrow(
                new DataIntegrityViolationException("duplicate", new SQLException("duplicate key", "23505")));

        assertThatThrownBy(() -> examinationResultService.register(id, examInstitutionId, "요약", null))
                .isInstanceOf(BaseException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.EXAMINATION_RESULT_ALREADY_EXISTS);
    }

    @Test
    @DisplayName("UNIQUE가 아닌 다른 제약 위반은 숨기지 않고 그대로 던진다")
    void otherViolationIsRethrown() {
        UUID id = completedExamination();
        given(examinationResultRepository.saveAndFlush(any(ExaminationResult.class))).willThrow(
                new DataIntegrityViolationException("not null", new SQLException("null value", "23502")));

        assertThatThrownBy(() -> examinationResultService.register(id, examInstitutionId, "요약", null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("기관이 다르면 검사 상태와 관계없이 EXAMINATION_RESULT_ACCESS_DENIED이고 중복 확인·저장을 하지 않는다")
    void otherInstitutionIsDenied() {
        UUID id = UUID.randomUUID();
        examination(id, ExaminationStatus.IN_PROGRESS);

        assertThatThrownBy(() -> examinationResultService.register(id, UUID.randomUUID(), "요약", null))
                .isInstanceOf(BaseException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.EXAMINATION_RESULT_ACCESS_DENIED);
        then(examinationResultRepository).shouldHaveNoInteractions();
    }
}
