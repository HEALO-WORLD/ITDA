package healoworld.referral_service.global.exception;

import healoworld.referral_service.global.dto.ErrorResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.support.DefaultMessageSourceResolvable;
import org.springframework.data.core.PropertyReferenceException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
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
    public ResponseEntity<ErrorResponse> handleBaseException(BaseException e) {
        return ResponseEntity.status(e.getStatus()).body(ErrorResponse.of(e.getErrorCode()));
    }

    // @RequestHeader로 받기로 한 헤더(X-User-Id, X-Role 등)가 요청에 없을 때
    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ErrorResponse> handleMissingHeader(MissingRequestHeaderException e) {
        return ResponseEntity.status(ErrorCode.MISSING_HEADER.getStatus())
                .body(ErrorResponse.of(ErrorCode.MISSING_HEADER,
                        ErrorCode.MISSING_HEADER.getMessage() + " (" + e.getHeaderName() + ")"));
    }

    // @Valid @RequestBody DTO가 검증 어노테이션을 통과 못 할 때
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationExceptions(MethodArgumentNotValidException e){

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
    public ResponseEntity<ErrorResponse> handleInvalidInput(Exception e) {
        return ResponseEntity.status(ErrorCode.INVALID_INPUT.getStatus())
                .body(ErrorResponse.of(ErrorCode.INVALID_INPUT));
    }

    // 위 어디에도 해당하지 않는 모든 예외
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleException(Exception e) {
        // 없는 URL(404), 지원하지 않는 메서드(405) 같은 Spring 표준 웹 예외는 원래 상태 코드를 유지한다
        if (e instanceof org.springframework.web.ErrorResponse errorResponse) {
            return ResponseEntity.status(errorResponse.getStatusCode())
                    .body(ErrorResponse.of(ErrorCode.UNSUPPORTED_REQUEST));
        }

        log.error("Unhandled exception", e);   // 원인 추적용. 응답에는 내부 메시지를 노출하지 않는다
        return ResponseEntity.status(ErrorCode.INTERNAL_SERVER_ERROR.getStatus())
                .body(ErrorResponse.of(ErrorCode.INTERNAL_SERVER_ERROR));
    }
}
