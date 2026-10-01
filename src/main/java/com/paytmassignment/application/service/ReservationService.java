package com.paytmassignment.application.service;

import com.paytmassignment.application.dto.request.ReserveRequest;
import com.paytmassignment.application.dto.response.ReservationResponse;
import com.paytmassignment.application.exception.DeclineReason;
import com.paytmassignment.application.exception.DomainException;
import com.paytmassignment.domain.model.Reservation;
import com.paytmassignment.domain.model.ReservationStatus;
import com.paytmassignment.domain.model.Seat;
import com.paytmassignment.domain.model.Show;
import com.paytmassignment.domain.model.User;
import com.paytmassignment.domain.repository.ReservationRepository;
import com.paytmassignment.domain.repository.SeatRepository;
import com.paytmassignment.domain.repository.ShowRepository;
import com.paytmassignment.domain.repository.UserRepository;
import com.paytmassignment.infrastructure.metrics.ReservationMetrics;
import com.paytmassignment.infrastructure.observability.ReservationEventBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class ReservationService {

    private static final Logger log = LoggerFactory.getLogger(ReservationService.class);

    private final ShowRepository showRepository;
    private final SeatRepository seatRepository;
    private final ReservationRepository reservationRepository;
    private final UserRepository userRepository;
    private final ReservationMetrics metrics;
    private final ReservationEventBuffer events;

    public ReservationService(
            ShowRepository showRepository,
            SeatRepository seatRepository,
            ReservationRepository reservationRepository,
            UserRepository userRepository,
            ReservationMetrics metrics,
            ReservationEventBuffer events) {
        this.showRepository = showRepository;
        this.seatRepository = seatRepository;
        this.reservationRepository = reservationRepository;
        this.userRepository = userRepository;
        this.metrics = metrics;
        this.events = events;
    }

    @Transactional
    public ReservationResponse reserve(UUID showId, User user, ReserveRequest request) {
        List<String> seats = normalizeAndSort(request.seats());
        String requestHash = hashSeats(seats);
        String idempotencyKey = request.idempotency_key().trim();

        Optional<Reservation> existing =
                reservationRepository.findByUserIdAndShowIdAndIdempotencyKey(user.getId(), showId, idempotencyKey);
        if (existing.isPresent()) {
            return handleIdempotentReplay(existing.get(), requestHash);
        }

        Show show = showRepository.findById(showId)
                .orElseThrow(() -> DomainException.notFound(DeclineReason.SHOW_NOT_FOUND, "Show not found"));

        lockUser(user.getId());

        existing = reservationRepository.findByUserIdAndShowIdAndIdempotencyKey(user.getId(), showId, idempotencyKey);
        if (existing.isPresent()) {
            return handleIdempotentReplay(existing.get(), requestHash);
        }

        List<Seat> lockedSeats = seatRepository.lockByShowIdAndLabels(showId, seats);
        if (lockedSeats.size() != seats.size()) {
            recordDecline(
                    DeclineReason.UNKNOWN_SEAT, "UNKNOWN_SEAT", showId, user.getId(), null, seats, 404);
            throw DomainException.notFound(DeclineReason.UNKNOWN_SEAT, "One or more seats do not exist for this show");
        }

        for (Seat seat : lockedSeats) {
            if (!seat.isAvailable()) {
                recordDecline(DeclineReason.SEAT_TAKEN, "SEAT_TAKEN", showId, user.getId(), null, seats, 409);
                throw DomainException.conflict(
                        DeclineReason.SEAT_TAKEN,
                        "Seat already taken: " + seat.getSeatLabel());
            }
        }

        long alreadyHeld = seatRepository.countActiveSeatsForUser(showId, user.getId());
        if (alreadyHeld + seats.size() > show.getPerUserLimit()) {
            recordDecline(DeclineReason.PER_USER_LIMIT, "PER_USER_LIMIT", showId, user.getId(), null, seats, 409);
            throw DomainException.conflict(
                    DeclineReason.PER_USER_LIMIT,
                    "Per-user seat limit exceeded (limit=" + show.getPerUserLimit() + ")");
        }

        Instant now = Instant.now();
        UUID reservationId = UUID.randomUUID();
        long amountPaise = Math.multiplyExact(show.getPricePaise(), (long) seats.size());

        Reservation reservation = new Reservation(
                reservationId,
                show,
                user.getId(),
                amountPaise,
                ReservationStatus.CONFIRMED,
                idempotencyKey,
                requestHash,
                seats,
                now,
                null);

        reservationRepository.saveAndFlush(reservation);

        for (Seat seat : lockedSeats) {
            seat.confirm(reservationId, now);
        }
        seatRepository.saveAll(lockedSeats);

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                safeObservation("confirmed_metric", metrics::recordConfirmed);
                safeObservation("available_seats_gauge", () -> metrics.refreshAvailableGauge(showId));
                recordEvent("RESERVATION_CONFIRMED", showId, user.getId(), reservationId, seats, 201);
            }
        });

        return toResponse(reservation);
    }

    @Transactional
    public ReservationResponse cancel(UUID reservationId, User user) {
        Reservation reservation = reservationRepository.findById(reservationId)
                .orElseThrow(() -> DomainException.notFound(
                        DeclineReason.RESERVATION_NOT_FOUND, "Reservation not found"));

        if (!reservation.getUserId().equals(user.getId())) {
            throw DomainException.forbidden("Only the owning user may cancel this reservation");
        }

        if (!reservation.isActive()) {
            throw DomainException.conflict(
                    DeclineReason.VALIDATION,
                    "Reservation is not active (status=" + reservation.getStatus() + ")");
        }

        UUID showId = reservation.getShow().getId();
        lockUser(user.getId());

        List<String> labels = reservation.getSeatLabels();
        List<Seat> locked = seatRepository.lockByShowIdAndLabels(showId, labels);
        Instant now = Instant.now();

        for (Seat seat : locked) {
            if (reservationId.equals(seat.getReservationId())) {
                seat.release(now);
            }
        }
        seatRepository.saveAll(locked);
        reservation.cancel(now);
        reservationRepository.save(reservation);

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                safeObservation("available_seats_gauge", () -> metrics.refreshAvailableGauge(showId));
                recordEvent("CANCELLATION", showId, user.getId(), reservationId, labels, 200);
            }
        });

        return toResponse(reservation);
    }

    @Transactional(readOnly = true)
    public ReservationResponse getReservation(UUID reservationId, User user) {
        Reservation reservation = reservationRepository.findById(reservationId)
                .orElseThrow(() -> DomainException.notFound(
                        DeclineReason.RESERVATION_NOT_FOUND, "Reservation not found"));
        if (!reservation.getUserId().equals(user.getId()) && !user.isAdmin()) {
            throw DomainException.forbidden("Not allowed to view this reservation");
        }
        return toResponse(reservation);
    }

    private ReservationResponse handleIdempotentReplay(Reservation existing, String requestHash) {
        if (!existing.getRequestHash().equals(requestHash)) {
            recordDecline(
                    DeclineReason.IDEMPOTENCY_KEY_REUSED,
                    "IDEMPOTENCY_CONFLICT",
                    existing.getShow().getId(),
                    existing.getUserId(),
                    existing.getId(),
                    existing.getSeatLabels(),
                    409);
            throw DomainException.conflict(
                    DeclineReason.IDEMPOTENCY_KEY_REUSED,
                    "Idempotency key was already used with a different seat set");
        }
        safeObservation("idempotent_replay_metric", metrics::recordIdempotentReplay);
        recordEvent(
                "IDEMPOTENT_REPLAY",
                existing.getShow().getId(),
                existing.getUserId(),
                existing.getId(),
                existing.getSeatLabels(),
                201);
        return toResponse(existing);
    }

    private void recordDecline(
            DeclineReason reason,
            String eventType,
            UUID showId,
            UUID userId,
            UUID reservationId,
            List<String> seats,
            int httpStatus) {
        safeObservation("decline_metric", () -> metrics.recordDecline(reason));
        recordEvent(eventType, showId, userId, reservationId, seats, httpStatus);
    }

    private void recordEvent(
            String eventType,
            UUID showId,
            UUID userId,
            UUID reservationId,
            List<String> seats,
            int httpStatus) {
        safeObservation(
                "business_event",
                () -> events.record(eventType, showId, userId, reservationId, seats, httpStatus));
    }

    private static void safeObservation(String observation, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException ex) {
            log.warn("observability_failure type={} request_id={}", observation, MDC.get("request_id"));
        }
    }

    private void lockUser(UUID userId) {
        if (userRepository.lockById(userId) == null) {
            throw DomainException.forbidden("User no longer exists");
        }
    }

    private static List<String> normalizeAndSort(List<String> seats) {
        List<String> normalized = new ArrayList<>(ShowService.normalizeSeatLabels(seats));
        if (normalized.isEmpty()) {
            throw DomainException.badRequest("seats must not be empty");
        }
        normalized.sort(String::compareTo);
        return List.copyOf(normalized);
    }

    private static String hashSeats(List<String> seats) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            StringBuilder canonical = new StringBuilder();
            for (String seat : seats) {
                canonical.append(seat.length()).append(':').append(seat);
            }
            digest.update(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ReservationResponse toResponse(Reservation reservation) {
        return new ReservationResponse(
                reservation.getId(),
                reservation.getShow().getId(),
                reservation.getUserId(),
                reservation.getSeatLabels(),
                reservation.getAmountPaise(),
                reservation.getStatus().name().toLowerCase(Locale.ROOT));
    }
}
