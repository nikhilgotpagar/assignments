package com.paytmassignment.api.controller;

import com.paytmassignment.application.exception.DomainException;
import com.paytmassignment.infrastructure.observability.ReservationBusinessEvent;
import com.paytmassignment.infrastructure.observability.ReservationEventBuffer;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class LogsController {

    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = ReservationEventBuffer.CAPACITY;

    private final ReservationEventBuffer events;

    public LogsController(ReservationEventBuffer events) {
        this.events = events;
    }

    @GetMapping("/logs")
    public List<ReservationBusinessEvent> getLogs(
            @RequestParam(name = "limit", defaultValue = "100") int limit) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw DomainException.badRequest("limit must be between 1 and " + MAX_LIMIT);
        }
        return events.latest(limit);
    }
}
