package healoworld.slot_service.global.exception;

import healoworld.slot_service.global.dto.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.support.DefaultMessageSourceResolvable;
import org.springframework.data.core.PropertyReferenceException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    // 서비스 코드에서 throw new BaseException(ErrorCode.XXX)로 던질 때
    @ExceptionHandler(BaseException.class)
    public ResponseEntity<ErrorResponse> handleBaseException(BaseException e, HttpServletRequest request) {
        // 5xx는 서버 문제라 스택트레이스까지, 4xx는 비즈니스 규칙 위반이라 한 줄만 남긴다
        if (e.getStatus().is5xxServerError()) {
            log.error("{} - [{}] {}", requestLine(request), e.getErrorCode(), e.getMessage(), e);
        } else {
            log.warn("{} - [{}] {}", requestLine(request), e.getErrorCode(), e.getMessage());
        }
        return ResponseEntity.status(e.getStatus()).body(ErrorResponse.of(e.getErrorCode()));
    }

    // @RequestHeader로 받기로 한 헤더가 요청에 없을 때
    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ErrorResponse> handleMissingHeader(MissingRequestHeaderException e,
                                                             HttpServletRequest request) {
        log.warn("{} - 필수 헤더 누락: {}", requestLine(request), e.getHeaderName());
        return ResponseEntity.status(ErrorCode.MISSING_HEADER.getStatus())
                .body(ErrorResponse.of(ErrorCode.MISSING_HEADER,
                        ErrorCode.MISSING_HEADER.getMessage() + " (" + e.getHeaderName() + ")"));
    }

    // @Valid @RequestBody DTO가 검증 어노테이션을 통과 못 할 때
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationExceptions(MethodArgumentNotValidException e,
                                                                    HttpServletRequest request) {
        // 어떤 필드가 실패했는지만 남긴다 (요청 값에 환자 정보가 있을 수 있어 값은 기록하지 않음)
        List<String> failedFields = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getField)
                .toList();
        log.warn("{} - 요청 값 검증 실패: {}", requestLine(request), failedFields);

        String message = e.getBindingResult()
                .getFieldErrors()
                .stream()
                .findFirst()
                .map(DefaultMessageSourceResolvable::getDefaultMessage)
                .orElse(ErrorCode.VALIDATION_FAILED.getMessage());

        return ResponseEntity.status(ErrorCode.VALIDATION_FAILED.getStatus())
                .body(ErrorResponse.of(ErrorCode.VALIDATION_FAILED, message));
    }

    // 요청 JSON 형식이 깨졌거나 본문이 없을 때, 존재하지 않는 필드로 정렬을 요청했을 때(?sort=foo)
    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            PropertyReferenceException.class})
    public ResponseEntity<ErrorResponse> handleInvalidInput(Exception e, HttpServletRequest request) {
        log.warn("{} - 잘못된 요청 형식: {}", requestLine(request), e.getClass().getSimpleName());
        return ResponseEntity.status(ErrorCode.INVALID_INPUT.getStatus())
                .body(ErrorResponse.of(ErrorCode.INVALID_INPUT));
    }

    // 위 어디에도 해당하지 않는 모든 예외
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleException(Exception e, HttpServletRequest request) {
        // 없는 URL(404), 지원하지 않는 메서드(405) 같은 Spring 표준 웹 예외는 원래 상태 코드를 유지한다
        if (e instanceof org.springframework.web.ErrorResponse errorResponse) {
            log.warn("{} - {}", requestLine(request), errorResponse.getStatusCode());
            return ResponseEntity.status(errorResponse.getStatusCode())
                    .body(ErrorResponse.of(ErrorCode.UNSUPPORTED_REQUEST));
        }

        log.error("{} - 처리되지 않은 예외", requestLine(request), e);   // 원인 추적용. 응답에는 내부 메시지를 노출하지 않는다
        return ResponseEntity.status(ErrorCode.INTERNAL_SERVER_ERROR.getStatus())
                .body(ErrorResponse.of(ErrorCode.INTERNAL_SERVER_ERROR));
    }

    // 로그에 남길 요청 정보. 쿼리스트링은 환자 이름 등 개인정보가 섞일 수 있어 제외한다
    private String requestLine(HttpServletRequest request) {
        return request.getMethod() + " " + request.getRequestURI();
    }
}
