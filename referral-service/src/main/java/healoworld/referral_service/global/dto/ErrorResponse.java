package healoworld.referral_service.global.dto;


import healoworld.referral_service.global.exception.ErrorCode;

public record ErrorResponse(String code, String message) {

    public static ErrorResponse of(ErrorCode errorCode) {
        return of(errorCode, errorCode.getMessage());
    }

    public static ErrorResponse of(ErrorCode errorCode, String message) {
        return new ErrorResponse(errorCode.name(), message);
    }
}