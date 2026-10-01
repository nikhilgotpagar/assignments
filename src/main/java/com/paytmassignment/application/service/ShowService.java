package com.paytmassignment.application.service;

import com.paytmassignment.application.dto.request.CreateShowRequest;
import com.paytmassignment.application.dto.response.SeatView;
import com.paytmassignment.application.dto.response.ShowCreatedResponse;
import com.paytmassignment.application.dto.response.ShowResponse;
import com.paytmassignment.application.exception.DeclineReason;
import com.paytmassignment.application.exception.DomainException;
import com.paytmassignment.config.AppProperties;
import com.paytmassignment.domain.model.Seat;
import com.paytmassignment.domain.model.SeatStatus;
import com.paytmassignment.domain.model.Show;
import com.paytmassignment.domain.repository.SeatRepository;
import com.paytmassignment.domain.repository.ShowRepository;
import com.paytmassignment.infrastructure.metrics.ReservationMetrics;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class ShowService {

    private static final Logger log = LoggerFactory.getLogger(ShowService.class);

    private final ShowRepository showRepository;
    private final SeatRepository seatRepository;
    private final AppProperties appProperties;
    private final ReservationMetrics metrics;

    public ShowService(
            ShowRepository showRepository,
            SeatRepository seatRepository,
            AppProperties appProperties,
            ReservationMetrics metrics) {
        this.showRepository = showRepository;
        this.seatRepository = seatRepository;
        this.appProperties = appProperties;
        this.metrics = metrics;
    }

    @Transactional
    public ShowCreatedResponse createShow(CreateShowRequest request) {
        List<String> uniqueSeats = normalizeSeatLabels(request.seats());
        if (uniqueSeats.isEmpty()) {
            throw DomainException.badRequest("seats must not be empty");
        }

        int limit = request.per_user_limit() != null
                ? request.per_user_limit()
                : appProperties.perUserLimitDefault();

        Instant now = Instant.now();
        Show show = new Show(
                UUID.randomUUID(),
                request.name().trim(),
                request.price_paise(),
                limit,
                uniqueSeats.size(),
                now);
        showRepository.save(show);

        List<Seat> seats = new ArrayList<>(uniqueSeats.size());
        for (String label : uniqueSeats) {
            seats.add(new Seat(UUID.randomUUID(), show, label, SeatStatus.AVAILABLE, now));
        }
        seatRepository.saveAll(seats);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    metrics.registerShow(show.getId());
                } catch (RuntimeException ex) {
                    log.warn(
                            "observability_failure type=available_seats_gauge show_id={} request_id={}",
                            show.getId(),
                            MDC.get("request_id"));
                }
            }
        });

        List<SeatView> views = uniqueSeats.stream()
                .map(label -> new SeatView(label, SeatStatus.AVAILABLE.name().toLowerCase(Locale.ROOT)))
                .toList();

        return new ShowCreatedResponse(
                show.getId(),
                show.getName(),
                show.getPricePaise(),
                show.getPerUserLimit(),
                show.getTotalSeats(),
                views);
    }

    @Transactional(readOnly = true)
    public ShowResponse getShow(UUID showId) {
        Show show = showRepository.findById(showId)
                .orElseThrow(() -> DomainException.notFound(DeclineReason.SHOW_NOT_FOUND, "Show not found"));

        List<Seat> seats = seatRepository.findByShowIdOrderBySeatLabelAsc(showId);
        int available = 0;
        int held = 0;
        int confirmed = 0;
        List<SeatView> views = new ArrayList<>(seats.size());
        for (Seat seat : seats) {
            switch (seat.getStatus()) {
                case AVAILABLE -> available++;
                case HELD -> held++;
                case CONFIRMED -> confirmed++;
            }
            views.add(new SeatView(
                    seat.getSeatLabel(),
                    seat.getStatus().name().toLowerCase(Locale.ROOT)));
        }

        if (available + held + confirmed != show.getTotalSeats()) {
            throw new IllegalStateException("Reconciliation invariant broken for show " + showId);
        }
        return new ShowResponse(
                show.getId(),
                show.getName(),
                show.getPricePaise(),
                show.getPerUserLimit(),
                show.getTotalSeats(),
                available,
                held,
                confirmed,
                views);
    }

    static List<String> normalizeSeatLabels(List<String> seats) {
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String seat : seats) {
            if (seat == null || seat.isBlank()) {
                throw DomainException.badRequest("seat labels must be non-blank");
            }
            unique.add(seat.trim());
        }
        return List.copyOf(unique);
    }
}
