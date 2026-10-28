package healoworld.referral_service.global.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    // common
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "유효성 검사에 실패했습니다."),
    MISSING_HEADER(HttpStatus.BAD_REQUEST, "필수 헤더가 누락되었습니다."),
    INVALID_INPUT(HttpStatus.BAD_REQUEST, "잘못된 요청입니다."),
    UNSUPPORTED_REQUEST(HttpStatus.BAD_REQUEST, "요청을 처리할 수 없습니다."),
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 내부 오류가 발생했습니다."),

    // examination
    EXAMINATION_NOT_FOUND(HttpStatus.NOT_FOUND, "검사를 찾을 수 없습니다."),
    EXAMINATION_ALREADY_EXISTS(HttpStatus.CONFLICT, "해당 의뢰의 검사가 이미 다른 예약으로 등록되어 있습니다."),
    INVALID_EXAMINATION_STATUS_TRANSITION(HttpStatus.CONFLICT, "허용되지 않는 검사 상태 변경입니다."),
    EXAMINATION_START_NOT_ALLOWED(HttpStatus.CONFLICT, "유효한 예약이 확인되지 않아 검사를 시작할 수 없습니다."),
    EXAMINATION_RESERVATION_NOT_CHANGEABLE(HttpStatus.CONFLICT, "이미 시작되었거나 완료된 검사는 예약을 변경할 수 없습니다."),
    EXAMINATION_NOT_COMPLETED(HttpStatus.CONFLICT, "검사가 완료된 후에만 결과를 등록할 수 있습니다."),
    EXAMINATION_RESULT_ALREADY_EXISTS(HttpStatus.CONFLICT, "이미 등록된 검사 결과가 있습니다.");

    private final HttpStatus status;
    private final String message;
}