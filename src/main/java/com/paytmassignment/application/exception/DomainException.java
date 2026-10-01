package com.paytmassignment.application.exception;

public class DomainException extends RuntimeException {

    private final DeclineReason reason;
    private final int httpStatus;

    public DomainException(DeclineReason reason, String message, int httpStatus) {
        super(message);
        this.reason = reason;
        this.httpStatus = httpStatus;
    }

    public DeclineReason getReason() {
        return reason;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    public static DomainException conflict(DeclineReason reason, String message) {
        return new DomainException(reason, message, 409);
    }

    public static DomainException notFound(DeclineReason reason, String message) {
        return new DomainException(reason, message, 404);
    }

    public static DomainException forbidden(String message) {
        return new DomainException(DeclineReason.FORBIDDEN, message, 403);
    }

    public static DomainException badRequest(String message) {
        return new DomainException(DeclineReason.VALIDATION, message, 400);
    }
}
