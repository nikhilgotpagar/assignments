package com.paytmassignment.infrastructure.observability;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ReservationBusinessEvent(
        Instant timestamp,
        String requestId,
        String eventType,
        UUID showId,
        UUID userId,
        UUID reservationId,
        List<String> seats,
        int httpStatus) {

    public ReservationBusinessEvent {
        seats = List.copyOf(seats);
    }
}
