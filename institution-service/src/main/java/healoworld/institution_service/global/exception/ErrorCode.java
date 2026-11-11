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
    INVALID_REFRESH_TOKEN(HttpStatus.UNAUTHORIZED, "유효하지 않은 Refresh Token입니다. 다시 로그인해 주세요."),
    REFRESH_TOKEN_REUSED(HttpStatus.UNAUTHORIZED, "이미 사용된 Refresh Token입니다. 보안을 위해 다시 로그인해 주세요."),

    // institution
    INSTITUTION_NOT_FOUND(HttpStatus.NOT_FOUND, "기관을 찾을 수 없습니다."),
    SIGNUP_NOT_ALLOWED_INSTITUTION(HttpStatus.BAD_REQUEST, "가입할 수 없는 기관입니다."),
    INVALID_INSTITUTION_NAME(HttpStatus.BAD_REQUEST, "기관명은 공백이 아닌 최대 100자여야 합니다."),
    INVALID_INSTITUTION_ADDRESS(HttpStatus.BAD_REQUEST, "주소는 공백이 아닌 최대 255자여야 합니다."),
    INVALID_CARE_INSTITUTION_CODE(HttpStatus.BAD_REQUEST, "의료기관은 숫자 8자리 요양기관기호가 필요하며, 운영사 기관은 가질 수 없습니다."),
    INSTITUTION_TYPE_NOT_ALLOWED(HttpStatus.BAD_REQUEST, "등록할 수 없는 기관 유형입니다."),
    DUPLICATE_CARE_INSTITUTION_CODE(HttpStatus.CONFLICT, "이미 등록된 요양기관기호입니다."),
    INSTITUTION_HAS_ACTIVE_PARTNERSHIP(HttpStatus.CONFLICT, "진행 중이거나 활성 상태인 협약이 있어 삭제할 수 없습니다."),
    INSTITUTION_HAS_USERS(HttpStatus.CONFLICT, "소속 사용자가 있어 삭제할 수 없습니다."),

    // partnership
    PARTNERSHIP_NOT_FOUND(HttpStatus.NOT_FOUND, "협약을 찾을 수 없습니다."),
    INVALID_PARTNERSHIP_STATUS(HttpStatus.CONFLICT, "현재 협약 상태에서는 처리할 수 없는 요청입니다."),
    INVALID_PARTNERSHIP_INSTITUTION(HttpStatus.BAD_REQUEST, "협약은 의료기관이 검사기관에 요청해야 합니다."),
    PARTNERSHIP_ALREADY_IN_PROGRESS(HttpStatus.CONFLICT, "이미 요청 중이거나 체결된 협약이 있습니다."),

    // equipment
    DUPLICATE_EQUIPMENT_NAME(HttpStatus.CONFLICT, "해당 기관에 같은 이름의 장비가 이미 등록되어 있습니다."),
    INSTITUTION_NOT_AVAILABLE(HttpStatus.CONFLICT, "활성 검사기관에만 장비를 등록할 수 있습니다."),
    DUPLICATE_EQUIPMENT_EXAM_TYPE(HttpStatus.CONFLICT, "이미 등록된 장비·검사 유형 연결입니다."),
    EQUIPMENT_RECOVERY_REQUIRED(HttpStatus.CONFLICT, "진행 중인 장비 중단의 복구 확인이 필요합니다."),
    EQUIPMENT_NOT_AVAILABLE(HttpStatus.CONFLICT, "현재 검사에 사용할 수 없는 장비입니다."),
    OPERATING_HOURS_NOT_AVAILABLE(HttpStatus.CONFLICT, "해당 날짜에 설정된 장비 운영시간이 없습니다."),

    // exam type
    DUPLICATE_EXAM_TYPE(HttpStatus.CONFLICT, "동일한 이름과 검사 방식의 검사 유형이 이미 등록되어 있습니다."),

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
