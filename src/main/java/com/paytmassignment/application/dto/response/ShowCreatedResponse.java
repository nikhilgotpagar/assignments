package com.paytmassignment.application.dto.response;

import java.util.List;
import java.util.UUID;

public record ShowCreatedResponse(
        UUID id,
        String name,
        long price_paise,
        int per_user_limit,
        int total_seats,
        List<SeatView> seats) {
}
