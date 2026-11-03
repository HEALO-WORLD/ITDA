package healoworld.institution_service.global.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    // user
    DUPLICATE_LOGIN_ID(HttpStatus.CONFLICT, "이미 사용 중인 아이디입니다."),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "아이디 또는 비밀번호가 올바르지 않습니다."),
    USER_NOT_APPROVED(HttpStatus.FORBIDDEN, "미승인 아이디입니다. 승인 후 이용해주세요."),
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다."),
    USER_ALREADY_APPROVED(HttpStatus.CONFLICT, "이미 승인된 사용자입니다."),

    // institution
    INSTITUTION_NOT_FOUND(HttpStatus.NOT_FOUND, "기관을 찾을 수 없습니다."),
    SIGNUP_NOT_ALLOWED_INSTITUTION(HttpStatus.BAD_REQUEST, "가입할 수 없는 기관입니다."),

    // equipment
    INSTITUTION_NOT_AVAILABLE(HttpStatus.CONFLICT, "활성 검사기관에만 장비를 등록할 수 있습니다."),

    // common
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "유효성 검사에 실패했습니다."),
    MISSING_HEADER(HttpStatus.BAD_REQUEST, "필수 헤더가 누락되었습니다."),
    INVALID_INPUT(HttpStatus.BAD_REQUEST, "잘못된 요청입니다."),
    UNSUPPORTED_REQUEST(HttpStatus.BAD_REQUEST, "요청을 처리할 수 없습니다."),
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 내부 오류가 발생했습니다."),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 리소스를 찾을 수 없습니다."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "요청을 처리할 권한이 없습니다.");

    private final HttpStatus status;
    private final String message;
}