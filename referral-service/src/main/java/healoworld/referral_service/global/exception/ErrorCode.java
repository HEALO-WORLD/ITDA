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
    NOTIFICATION_ACCESS_DENIED(HttpStatus.FORBIDDEN, "본인의 알림만 처리할 수 있습니다."),

    // examination
    EXAMINATION_NOT_FOUND(HttpStatus.NOT_FOUND, "검사를 찾을 수 없습니다."),
    EXAMINATION_ALREADY_EXISTS(HttpStatus.CONFLICT, "해당 의뢰의 검사가 이미 다른 예약으로 등록되어 있습니다."),
    INVALID_EXAMINATION_STATUS_TRANSITION(HttpStatus.CONFLICT, "허용되지 않는 검사 상태 변경입니다."),
    EXAMINATION_START_NOT_ALLOWED(HttpStatus.CONFLICT, "유효한 예약이 확인되지 않아 검사를 시작할 수 없습니다."),
    EXAMINATION_RESERVATION_NOT_CHANGEABLE(HttpStatus.CONFLICT, "이미 시작되었거나 완료된 검사는 예약을 변경할 수 없습니다."),
    EXAMINATION_NOT_COMPLETED(HttpStatus.CONFLICT, "검사가 완료된 후에만 결과를 등록할 수 있습니다."),
    EXAMINATION_RESULT_ALREADY_EXISTS(HttpStatus.CONFLICT, "이미 등록된 검사 결과가 있습니다."),
    EXAMINATION_RESULT_ACCESS_DENIED(HttpStatus.FORBIDDEN, "해당 검사 결과를 등록할 권한이 없습니다.");

    private final HttpStatus status;
    private final String message;
}