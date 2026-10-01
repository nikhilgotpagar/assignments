package com.paytmassignment.infrastructure.metrics;

import com.paytmassignment.application.exception.DeclineReason;
import com.paytmassignment.domain.model.SeatStatus;
import com.paytmassignment.domain.repository.ShowRepository;
import com.paytmassignment.domain.repository.SeatRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

@Component
public class ReservationMetrics implements ApplicationRunner {

    private final MeterRegistry registry;
    private final SeatRepository seatRepository;
    private final ShowRepository showRepository;
    private final Counter confirmed;
    private final Map<DeclineReason, Counter> declines = new ConcurrentHashMap<>();
    private final Map<UUID, AtomicLong> availableGauges = new ConcurrentHashMap<>();

    public ReservationMetrics(
            MeterRegistry registry, SeatRepository seatRepository, ShowRepository showRepository) {
        this.registry = registry;
        this.seatRepository = seatRepository;
        this.showRepository = showRepository;
        this.confirmed = Counter.builder("reservations_confirmed_total")
                .description("Reservations successfully confirmed")
                .register(registry);
    }

    public void recordConfirmed() {
        confirmed.increment();
    }

    public void recordDecline(DeclineReason reason) {
        declines.computeIfAbsent(reason, r -> Counter.builder("reservations_declined_total")
                        .description("Reservations declined by reason")
                        .tag("reason", mapReason(r))
                        .register(registry))
                .increment();
    }

    public void recordIdempotentReplay() {
        recordDecline(DeclineReason.IDEMPOTENT_REPLAY);
    }

    public void registerShow(UUID showId) {
        refreshAvailableGauge(showId);
    }

    public synchronized void refreshAvailableGauge(UUID showId) {
        availableGauges.computeIfAbsent(showId, id -> {
            AtomicLong gauge = new AtomicLong();
            registry.gauge("seats_available", Tags.of("show_id", id.toString()), gauge, AtomicLong::get);
            return gauge;
        }).set(seatRepository.countByShowIdAndStatus(showId, SeatStatus.AVAILABLE));
    }

    @Override
    public void run(ApplicationArguments args) {
        showRepository.findAll().forEach(show -> registerShow(show.getId()));
    }

    private static String mapReason(DeclineReason reason) {
        return switch (reason) {
            case SEAT_TAKEN -> "seat_taken";
            case PER_USER_LIMIT -> "per_user_limit";
            case IDEMPOTENT_REPLAY -> "idempotent_replay";
            case IDEMPOTENCY_KEY_REUSED -> "idempotency_key_reused";
            default -> reason.name().toLowerCase();
        };
    }
}
