package healoworld.slot_service.global.exception;

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

    // slot
    SLOT_START_NOT_FUTURE(HttpStatus.BAD_REQUEST,"시작 일시는 현재 이후여야 합니다."),
    SLOT_INVALID_TIME_RANGE(HttpStatus.BAD_REQUEST,"종료 일시는 시작 일시 이후여야 합니다."),
    SLOT_DATE_MISMATCH(HttpStatus.BAD_REQUEST,"슬롯의 시작과 종료는 한국 시간 기준 같은 날짜여야 합니다."),
    SLOT_TIME_OVERLAP(HttpStatus.CONFLICT,"동일 장비에 시간대가 겹치는 슬롯이 있습니다."),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 리소스를 찾을 수 없습니다."),
    SLOT_INVALID_SEARCH_RANGE(HttpStatus.BAD_REQUEST, "검색 종료 일시는 시작 일시보다 이후여야 합니다."),
    SLOT_SEARCH_RANGE_EXCEEDED(HttpStatus.BAD_REQUEST, "검색 기간은 최대 31일입니다."),
    SLOT_INVALID_SEARCH_STATUS(HttpStatus.BAD_REQUEST, "검색 모드에서는 AVAILABLE 상태만 조회할 수 있습니다."),
    REFERENCE_DATA_NOT_READY(HttpStatus.CONFLICT, "조회에 필요한 기관·협약 정보가 준비되지 않았습니다."),
    SLOT_NOT_FOUND(HttpStatus.NOT_FOUND, "슬롯을 찾을 수 없습니다."),
    SLOT_NOT_RELEASABLE(HttpStatus.CONFLICT, "요청한 예약으로 확보·예약된 슬롯이 아닙니다."),
    ADMIN_ONLY(HttpStatus.FORBIDDEN, "관리자만 접근 권한이 있습니다."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "요청을 처리할 권한이 없습니다."),
    SLOT_OUTSIDE_OPERATING_HOURS(HttpStatus.BAD_REQUEST, "슬롯은 장비 운영시간 안에 있어야 합니다."),
    SLOT_DURATION_MISMATCH(HttpStatus.BAD_REQUEST, "슬롯 길이는 장비별 검사 소요시간 이상이어야 합니다."),
    SLOT_GENERATION_EMPTY(HttpStatus.BAD_REQUEST, "생성 가능한 미래 슬롯이 없습니다."),

    // equipment downtime
    DOWNTIME_INVALID_TIME_RANGE(HttpStatus.BAD_REQUEST, "중단 종료 일시는 시작 일시 이후여야 합니다."),
    DOWNTIME_END_NOT_FUTURE(HttpStatus.BAD_REQUEST, "중단 종료 일시는 현재 이후여야 합니다."),
    DOWNTIME_OVERLAP(HttpStatus.CONFLICT, "같은 장비에 기간이 겹치는 중단이 이미 있습니다."),
    SLOT_IN_DOWNTIME(HttpStatus.CONFLICT, "장비 중단 기간과 겹치는 슬롯은 등록할 수 없습니다."),

    // equipment
    REFERENCE_NOT_AVAILABLE(HttpStatus.CONFLICT,"장비 또는 운영시간을 사용할 수 없습니다."),
    REFERENCE_SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE,"기준정보 서비스를 일시적으로 사용할 수 없습니다."),
    REFERENCE_RESPONSE_INVALID(HttpStatus.BAD_GATEWAY,"기준정보 서비스의 응답이 올바르지 않습니다."),

    // dead letter
    DEAD_LETTER_NOT_FOUND(HttpStatus.NOT_FOUND, "실패 메시지를 찾을 수 없습니다."),
    DEAD_LETTER_ALREADY_RESOLVED(HttpStatus.CONFLICT, "이미 재처리되었거나 무시된 실패 메시지입니다."),
    DEAD_LETTER_REPUBLISH_FAILED(HttpStatus.SERVICE_UNAVAILABLE, "메시지를 다시 발행하지 못했습니다. 잠시 후 다시 시도해 주세요.");

    private final HttpStatus status;
    private final String message;
}