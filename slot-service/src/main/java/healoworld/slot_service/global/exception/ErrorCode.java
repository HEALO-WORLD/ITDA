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
    REFERENCE_DATA_NOT_READY(HttpStatus.CONFLICT, "조회에 필요한 기관·협약 정보가 준비되지 않았습니다.");

    private final HttpStatus status;
    private final String message;
}