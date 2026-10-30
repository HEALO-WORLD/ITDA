package healoworld.referral_service.global.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    // referral
    REFERRAL_ACCESS_DENIED(HttpStatus.FORBIDDEN, "의뢰에 대한 권한이 없습니다."),
    REFERRAL_NOT_FOUND(HttpStatus.NOT_FOUND, "의뢰를 찾을 수 없습니다."),
    INVALID_REFERRAL_STATUS(HttpStatus.CONFLICT, "현재 의뢰상태에서는 처리할 수 없습니다."),

    // reservation
    RESERVATION_ACCESS_DENIED(HttpStatus.FORBIDDEN, "예약에 대한 권한이 없습니다."),
    RESERVATION_NOT_FOUND(HttpStatus.NOT_FOUND, "예약을 찾을 수 없습니다."),
    INVALID_RESERVATION_STATUS(HttpStatus.CONFLICT, "현재 예약상태에서는 처리할 수 없습니다."),
    INVALID_FAILURE_REASON(HttpStatus.BAD_REQUEST, "유효하지 않은 실패 사유입니다."),

    // common
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "유효성 검사에 실패했습니다."),
    MISSING_HEADER(HttpStatus.BAD_REQUEST, "필수 헤더가 누락되었습니다."),
    INVALID_INPUT(HttpStatus.BAD_REQUEST, "잘못된 요청입니다."),
    UNSUPPORTED_REQUEST(HttpStatus.BAD_REQUEST, "요청을 처리할 수 없습니다."),
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 내부 오류가 발생했습니다."),

    // notification
    NOTIFICATION_NOT_FOUND(HttpStatus.NOT_FOUND, "알림을 찾을 수 없습니다."),
    NOTIFICATION_ACCESS_DENIED(HttpStatus.FORBIDDEN, "본인의 알림만 처리할 수 있습니다.");

    private final HttpStatus status;
    private final String message;
}