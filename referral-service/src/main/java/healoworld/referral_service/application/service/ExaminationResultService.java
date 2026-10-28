package healoworld.referral_service.application.service;

import healoworld.referral_service.domain.entity.Examination;
import healoworld.referral_service.domain.entity.ExaminationResult;
import healoworld.referral_service.domain.enums.ExaminationStatus;
import healoworld.referral_service.global.exception.BaseException;
import healoworld.referral_service.global.exception.ErrorCode;
import healoworld.referral_service.infrastructure.repository.ExaminationRepository;
import healoworld.referral_service.infrastructure.repository.ExaminationResultRepository;
import healoworld.referral_service.presentation.dto.response.ExaminationResultCreateResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.SQLException;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ExaminationResultService {

    // PostgreSQL unique_violation
    private static final String UNIQUE_VIOLATION_SQL_STATE = "23505";

    private final ExaminationRepository examinationRepository;
    private final ExaminationResultRepository examinationResultRepository;

    // 완료된 검사에만 결과를 한 번 등록한다. 검사·의뢰·예약 상태는 바꾸지 않는다
    @Transactional
    public ExaminationResultCreateResponse register(UUID examinationId, String summary, String notes) {
        if (summary == null || summary.isBlank()) {
            throw new BaseException(ErrorCode.VALIDATION_FAILED);
        }

        Examination examination = examinationRepository.findById(examinationId)
                .orElseThrow(() -> new BaseException(ErrorCode.EXAMINATION_NOT_FOUND));
        if (examination.getStatus() != ExaminationStatus.COMPLETED) {
            throw new BaseException(ErrorCode.EXAMINATION_NOT_COMPLETED);
        }
        // 대부분의 중복 요청은 여기서 걸러진다. 동시에 들어온 요청은 둘 다 통과할 수 있으므로 아래 UNIQUE 제약이 최종 방어선이다
        if (examinationResultRepository.existsByExaminationId(examinationId)) {
            throw new BaseException(ErrorCode.EXAMINATION_RESULT_ALREADY_EXISTS);
        }

        try {
            ExaminationResult result = examinationResultRepository.saveAndFlush(
                    ExaminationResult.register(examination, summary, notes));
            return ExaminationResultCreateResponse.from(result);
        } catch (DataIntegrityViolationException e) {
            // 제약 위반 후 PostgreSQL 트랜잭션은 중단 상태라 추가 조회 없이 바로 예외를 던져 롤백한다
            if (isUniqueViolation(e)) {
                throw new BaseException(ErrorCode.EXAMINATION_RESULT_ALREADY_EXISTS);
            }
            throw e;
        }
    }

    private boolean isUniqueViolation(DataIntegrityViolationException e) {
        return NestedExceptionUtils.getMostSpecificCause(e) instanceof SQLException sqlException
                && UNIQUE_VIOLATION_SQL_STATE.equals(sqlException.getSQLState());
    }
}
