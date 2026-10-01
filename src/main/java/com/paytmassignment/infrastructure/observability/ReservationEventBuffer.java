package com.paytmassignment.infrastructure.observability;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

@Component
public class ReservationEventBuffer {

    public static final int CAPACITY = 500;
    private static final Logger log = LoggerFactory.getLogger(ReservationEventBuffer.class);
    private static final Set<String> LOGGED_EVENT_TYPES = Set.of(
            "RESERVATION_CONFIRMED",
            "SEAT_TAKEN",
            "PER_USER_LIMIT",
            "IDEMPOTENT_REPLAY",
            "IDEMPOTENCY_CONFLICT",
            "CANCELLATION",
            "UNKNOWN_SEAT");

    private final ArrayDeque<ReservationBusinessEvent> events = new ArrayDeque<>(CAPACITY);

    public void record(
            String eventType,
            UUID showId,
            UUID userId,
            UUID reservationId,
            List<String> seats,
            int httpStatus) {
        String requestId = MDC.get("request_id");
        ReservationBusinessEvent event = new ReservationBusinessEvent(
                Instant.now(),
                requestId == null || requestId.isBlank() ? UUID.randomUUID().toString() : requestId,
                eventType,
                showId,
                userId,
                reservationId,
                seats,
                httpStatus);
        synchronized (events) {
            if (events.size() == CAPACITY) {
                events.removeFirst();
            }
            events.addLast(event);
        }
        if (LOGGED_EVENT_TYPES.contains(eventType)) {
            log.info(
                    "reservation_event event_type={} request_id={} show_id={} user_id={} reservation_id={} seats={} http_status={}",
                    eventType,
                    event.requestId(),
                    showId,
                    userId,
                    reservationId,
                    seats,
                    httpStatus);
        }
    }

    public List<ReservationBusinessEvent> latest(int limit) {
        synchronized (events) {
            List<ReservationBusinessEvent> latest = new ArrayList<>(Math.min(limit, events.size()));
            var iterator = events.descendingIterator();
            while (iterator.hasNext() && latest.size() < limit) {
                latest.add(iterator.next());
            }
            return List.copyOf(latest);
        }
    }
}
