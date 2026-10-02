package com.paytmassignment.infrastructure.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.paytmassignment.application.exception.DeclineReason;
import com.paytmassignment.domain.model.SeatStatus;
import com.paytmassignment.domain.repository.SeatRepository;
import com.paytmassignment.domain.repository.ShowRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ReservationMetricsTest {

    @Test
    void registersRequiredDeclineSeriesBeforeAnyDeclinesOccur() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new ReservationMetrics(registry, mock(SeatRepository.class), mock(ShowRepository.class));

        assertEquals(
                0.0,
                registry.get("reservations_declined_total")
                        .tag("reason", "seat_taken")
                        .counter()
                        .count());
        assertEquals(
                0.0,
                registry.get("reservations_declined_total")
                        .tag("reason", "per_user_limit")
                        .counter()
                        .count());
        assertEquals(
                0.0,
                registry.get("reservations_declined_total")
                        .tag("reason", "idempotent_replay")
                        .counter()
                        .count());
    }

    @Test
    void recordsBusinessCountersAndReplayDoesNotCountAsConfirmation() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ReservationMetrics metrics = new ReservationMetrics(registry, mock(SeatRepository.class), mock(ShowRepository.class));

        metrics.recordConfirmed();
        metrics.recordDecline(DeclineReason.SEAT_TAKEN);
        metrics.recordDecline(DeclineReason.PER_USER_LIMIT);
        metrics.recordIdempotentReplay();

        assertEquals(1.0, registry.get("reservations_confirmed_total").counter().count());
        assertEquals(
                1.0,
                registry.get("reservations_declined_total").tag("reason", "seat_taken").counter().count());
        assertEquals(
                1.0,
                registry.get("reservations_declined_total").tag("reason", "per_user_limit").counter().count());
        assertEquals(
                1.0,
                registry.get("reservations_declined_total").tag("reason", "idempotent_replay").counter().count());
    }

    @Test
    void availableSeatsGaugeReadsTheCurrentDatabaseState() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SeatRepository seats = mock(SeatRepository.class);
        UUID showId = UUID.randomUUID();
        AtomicInteger available = new AtomicInteger(8);
        when(seats.countByShowIdAndStatus(showId, SeatStatus.AVAILABLE))
                .thenAnswer(call -> (long) available.get());
        ReservationMetrics metrics = new ReservationMetrics(registry, seats, mock(ShowRepository.class));

        metrics.registerShow(showId);
        assertEquals(
                8.0,
                registry.get("seats_available").tag("show_id", showId.toString()).gauge().value());

        available.set(5);
        metrics.refreshAvailableGauge(showId);
        assertEquals(
                5.0,
                registry.get("seats_available").tag("show_id", showId.toString()).gauge().value());
    }
}
