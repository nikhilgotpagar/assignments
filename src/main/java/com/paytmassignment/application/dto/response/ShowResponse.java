package com.paytmassignment.application.dto.response;

import java.util.List;
import java.util.UUID;

public record ShowResponse(
        UUID id,
        String name,
        long price_paise,
        int per_user_limit,
        int total_seats,
        int available,
        int held,
        int confirmed,
        List<SeatView> seats) {
}
