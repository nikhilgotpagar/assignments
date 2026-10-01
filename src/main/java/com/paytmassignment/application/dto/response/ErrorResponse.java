package com.paytmassignment.application.dto.response;

public record ErrorResponse(
        String error,
        String reason,
        String message,
        String request_id) {
}
