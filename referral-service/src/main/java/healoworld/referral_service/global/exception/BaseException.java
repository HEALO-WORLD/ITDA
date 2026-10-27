package healoworld.referral_service.global.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public class BaseException extends RuntimeException{
    private final ErrorCode errorCode;
    private final HttpStatus status;
    private final String message;

    public BaseException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
        this.status = errorCode.getStatus();
        this.message = errorCode.getMessage();
    }
}