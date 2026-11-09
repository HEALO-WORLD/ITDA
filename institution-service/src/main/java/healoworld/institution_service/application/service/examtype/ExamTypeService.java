package healoworld.institution_service.application.service.examtype;

import healoworld.institution_service.domain.entity.ExamType;
import healoworld.institution_service.domain.enums.Modality;
import healoworld.institution_service.domain.enums.Role;
import healoworld.institution_service.global.dto.PagedResponse;
import healoworld.institution_service.global.exception.BaseException;
import healoworld.institution_service.global.exception.ErrorCode;
import healoworld.institution_service.infrastructure.repository.ExamTypeRepository;
import healoworld.institution_service.presentation.dto.request.ExamTypeCreateRequest;
import healoworld.institution_service.presentation.dto.request.ExamTypeUpdateRequest;
import healoworld.institution_service.presentation.dto.response.ExamTypeCreateResponse;
import healoworld.institution_service.presentation.dto.response.ExamTypeResponse;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ExamTypeService {

    private static final String UNIQUE_EXAM_TYPE_INDEX = "uq_exam_type_active_name_modality";

    private final ExamTypeRepository examTypeRepository;

    @Transactional
    public ExamTypeCreateResponse createExamType(
            ExamTypeCreateRequest request,
            UUID userId,
            Role userRole
    ) {
        if (userId == null || userRole == null) {
            throw new BaseException(ErrorCode.INVALID_INPUT);
        }

        if (userRole != Role.ADMIN) {
            throw new BaseException(ErrorCode.FORBIDDEN);
        }

        validateRequest(request);

        ExamType examType = ExamType.create(
                request.getName(),
                request.getModality()
        );

        if (examTypeRepository.existsByNameAndModalityAndDeletedAtIsNull(
                examType.getName(), examType.getModality())) {
            throw new BaseException(ErrorCode.DUPLICATE_EXAM_TYPE);
        }

        ExamType savedExamType;
        try {
            savedExamType = examTypeRepository.saveAndFlush(examType);
        } catch (DataIntegrityViolationException exception) {
            throw translateConstraint(exception);
        }

        return ExamTypeCreateResponse.from(savedExamType);
    }

    @Transactional(readOnly = true)
    public PagedResponse<ExamTypeResponse> getExamTypes(
            UUID userId,
            Role userRole,
            Modality modality,
            Integer page,
            Integer size
    ) {
        if (userId == null || userRole == null) {
            throw new BaseException(ErrorCode.INVALID_INPUT);
        }

        if (userRole != Role.REQUESTER && userRole != Role.EXAMINER && userRole != Role.ADMIN) {
            throw new BaseException(ErrorCode.FORBIDDEN);
        }

        if (page == null || size == null
                || page < 0 || size < 1 || size > 100) {
            throw new BaseException(ErrorCode.INVALID_INPUT);
        }

        Pageable pageable = PageRequest.of(page, size);

        Page<ExamType> examTypes = examTypeRepository.findExamTypes(modality, pageable);

        Page<ExamTypeResponse> responses = examTypes.map(ExamTypeResponse::from);

        return PagedResponse.from(responses);
    }

    @Transactional
    public ExamTypeResponse updateExamType(
            UUID examTypeId,
            ExamTypeUpdateRequest request,
            UUID userId,
            Role userRole
    ) {
        if (examTypeId == null || userId == null || userRole == null) {
            throw new BaseException(ErrorCode.INVALID_INPUT);
        }

        if (userRole != Role.ADMIN) {
            throw new BaseException(ErrorCode.FORBIDDEN);
        }

        validateUpdateRequest(request);

        ExamType examType = examTypeRepository.findByIdAndDeletedAtIsNull(examTypeId).orElseThrow(() ->
                new BaseException(ErrorCode.RESOURCE_NOT_FOUND)
        );

        String normalizedName = request.getName().trim();
        if (examTypeRepository.existsByNameAndModalityAndDeletedAtIsNullAndIdNot(
                normalizedName, examType.getModality(), examTypeId)) {
            throw new BaseException(ErrorCode.DUPLICATE_EXAM_TYPE);
        }

        examType.update(normalizedName);
        try {
            examTypeRepository.flush();
        } catch (DataIntegrityViolationException exception) {
            throw translateConstraint(exception);
        }

        return ExamTypeResponse.from(examType);
    }

    private RuntimeException translateConstraint(DataIntegrityViolationException exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation
                    && "23505".equals(violation.getSQLState())
                    && UNIQUE_EXAM_TYPE_INDEX.equals(violation.getConstraintName())) {
                return new BaseException(ErrorCode.DUPLICATE_EXAM_TYPE);
            }
        }
        return exception;
    }

    private void validateRequest(ExamTypeCreateRequest request) {
        if (request == null
                || request.getName() == null
                || request.getName().isBlank()
                || request.getName().length() > 50
                || request.getModality() == null) {
            throw new BaseException(ErrorCode.INVALID_INPUT);
        }
    }

    private void validateUpdateRequest(ExamTypeUpdateRequest request) {
        if (request == null || request.getName() == null
                || request.getName().isBlank() || request.getName().length() > 50) {
            throw new BaseException(ErrorCode.INVALID_INPUT);
        }
    }
}
