package com.paytmassignment.application.exception;

public enum DeclineReason {
    SEAT_TAKEN,
    PER_USER_LIMIT,
    IDEMPOTENCY_KEY_REUSED,
    IDEMPOTENT_REPLAY,
    UNKNOWN_SEAT,
    RESERVATION_NOT_FOUND,
    UNAUTHORIZED,
    FORBIDDEN,
    SHOW_NOT_FOUND,
    VALIDATION
}
