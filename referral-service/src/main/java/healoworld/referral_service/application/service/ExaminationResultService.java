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
import healoworld.referral_service.presentation.dto.response.ExaminationResultCreateResponse;
import healoworld.referral_service.presentation.dto.response.ExaminationResultResponse;

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
    private final ReferralRepository referralRepository;


    // ========================================
    // 1. 검사 결과 등록
    // ========================================

    // 완료된 검사에만 결과를 한 번 등록한다.
    // 검사·의뢰·예약 상태는 변경하지 않는다.
    @Transactional
    public ExaminationResultCreateResponse register(
            UUID examinationId,
            UUID institutionId,
            String summary,
            String notes
    ) {
        if (summary == null || summary.isBlank()) {
            throw new BaseException(ErrorCode.VALIDATION_FAILED);
        }

        Examination examination = examinationRepository.findById(examinationId)
                .orElseThrow(() ->
                        new BaseException(ErrorCode.EXAMINATION_NOT_FOUND)
                );

        // 해당 검사를 수행하는 검사기관 소속인지 확인
        if (!examination.getExamInstitutionId().equals(institutionId)) {
            throw new BaseException(
                    ErrorCode.EXAMINATION_RESULT_ACCESS_DENIED
            );
        }

        // 검사가 완료되었는지 확인
        if (examination.getStatus() != ExaminationStatus.COMPLETED) {
            throw new BaseException(
                    ErrorCode.EXAMINATION_NOT_COMPLETED
            );
        }

        // 결과 중복 등록 방지
        if (examinationResultRepository.existsByExaminationId(examinationId)) {
            throw new BaseException(
                    ErrorCode.EXAMINATION_RESULT_ALREADY_EXISTS
            );
        }

        try {
            ExaminationResult result =
                    examinationResultRepository.saveAndFlush(
                            ExaminationResult.register(
                                    examination,
                                    summary,
                                    notes
                            )
                    );

            return ExaminationResultCreateResponse.from(result);

        } catch (DataIntegrityViolationException e) {

            // 동시 요청으로 UNIQUE 제약조건을 위반한 경우
            if (isUniqueViolation(e)) {
                throw new BaseException(
                        ErrorCode.EXAMINATION_RESULT_ALREADY_EXISTS
                );
            }

            throw e;
        }
    }


    // ========================================
    // 2. 의뢰기관용 검사 결과 조회
    // ========================================

    public ExaminationResultResponse getResultByReferralId(
            UUID referralId,
            UUID institutionId,
            Role role
    ) {
        // REQUESTER 역할만 허용
        if (role != Role.REQUESTER) {
            throw new BaseException(
                    ErrorCode.EXAMINATION_RESULT_READ_ACCESS_DENIED
            );
        }

        // 의뢰 조회
        Referral referral = referralRepository.findById(referralId)
                .orElseThrow(() ->
                        new BaseException(ErrorCode.REFERRAL_NOT_FOUND)
                );

        // 요청자가 해당 의뢰기관 소속인지 확인
        if (!referral.getReferringInstitutionId().equals(institutionId)) {
            throw new BaseException(
                    ErrorCode.EXAMINATION_RESULT_READ_ACCESS_DENIED
            );
        }

        // 의뢰에 연결된 검사 조회
        Examination examination =
                examinationRepository.findByReferralId(referralId)
                        .orElseThrow(() ->
                                new BaseException(ErrorCode.EXAMINATION_NOT_FOUND)
                        );

        return findResult(examination);
    }


    // ========================================
    // 3. 검사기관용 검사 결과 조회
    // ========================================

    public ExaminationResultResponse getResultByExaminationId(
            UUID examinationId,
            UUID institutionId,
            Role role
    ) {
        // EXAMINER 역할만 허용
        if (role != Role.EXAMINER) {
            throw new BaseException(
                    ErrorCode.EXAMINATION_RESULT_READ_ACCESS_DENIED
            );
        }

        // 검사 조회
        Examination examination = examinationRepository.findById(examinationId)
                .orElseThrow(() ->
                        new BaseException(ErrorCode.EXAMINATION_NOT_FOUND)
                );

        // 요청자가 해당 검사기관 소속인지 확인
        if (!examination.getExamInstitutionId().equals(institutionId)) {
            throw new BaseException(
                    ErrorCode.EXAMINATION_RESULT_READ_ACCESS_DENIED
            );
        }

        return findResult(examination);
    }


    // ========================================
    // 4. 검사 결과 공통 조회 메서드
    // ========================================

    // 두 조회 메서드에서 중복되던 로직을 분리
    private ExaminationResultResponse findResult(Examination examination) {

        ExaminationResult result = examinationResultRepository
                .findByExamination_Id(examination.getId())
                .orElseThrow(() ->
                        new BaseException(ErrorCode.EXAMINATION_RESULT_NOT_FOUND)
                );

        return ExaminationResultResponse.from(result);
    }


    // ========================================
    // 5. PostgreSQL UNIQUE 제약 위반 확인
    // ========================================

    private boolean isUniqueViolation(DataIntegrityViolationException e) {
        return NestedExceptionUtils.getMostSpecificCause(e)
                instanceof SQLException sqlException
                && UNIQUE_VIOLATION_SQL_STATE.equals(
                sqlException.getSQLState()
        );
    }
}