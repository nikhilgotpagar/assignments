package com.paytmassignment.application.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.paytmassignment.application.dto.request.ReserveRequest;
import com.paytmassignment.application.exception.DeclineReason;
import com.paytmassignment.application.exception.DomainException;
import com.paytmassignment.domain.model.Reservation;
import com.paytmassignment.domain.model.ReservationStatus;
import com.paytmassignment.domain.model.Seat;
import com.paytmassignment.domain.model.SeatStatus;
import com.paytmassignment.domain.model.Show;
import com.paytmassignment.domain.model.User;
import com.paytmassignment.domain.model.UserRole;
import com.paytmassignment.domain.repository.ReservationRepository;
import com.paytmassignment.domain.repository.SeatRepository;
import com.paytmassignment.domain.repository.ShowRepository;
import com.paytmassignment.domain.repository.UserRepository;
import com.paytmassignment.infrastructure.metrics.ReservationMetrics;
import com.paytmassignment.infrastructure.observability.ReservationEventBuffer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;

class ReservationObservabilityTest {

    private final UUID showId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final Show show = new Show(showId, "test", 100L, 4, 10, Instant.now());
    private final User user = new User(userId, "user-token", "test-user", UserRole.USER, Instant.now());
    private ShowRepository shows;
    private SeatRepository seats;
    private ReservationRepository reservations;
    private UserRepository users;
    private SimpleMeterRegistry registry;
    private ReservationEventBuffer events;
    private ReservationService service;

    @BeforeEach
    void setUp() {
        shows = mock(ShowRepository.class);
        seats = mock(SeatRepository.class);
        reservations = mock(ReservationRepository.class);
        users = mock(UserRepository.class);
        registry = new SimpleMeterRegistry();
        events = new ReservationEventBuffer();
        ReservationMetrics metrics = new ReservationMetrics(registry, seats, shows);
        service = new ReservationService(shows, seats, reservations, users, metrics, events);
        when(shows.findById(showId)).thenReturn(Optional.of(show));
        when(users.lockById(userId)).thenReturn(user);
    }

    @AfterEach
    void clearTransactionSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void confirmedReservationAndReplayRecordDistinctEventsAndCounters() {
        Seat seat = availableSeat("A1");
        when(reservations.findByUserIdAndShowIdAndIdempotencyKey(userId, showId, "key"))
                .thenAnswer(call -> Optional.ofNullable(savedReservation.get()));
        when(seats.lockByShowIdAndLabels(showId, List.of("A1"))).thenReturn(List.of(seat));
        when(seats.countActiveSeatsForUser(showId, userId)).thenReturn(0L);
        when(reservations.saveAndFlush(any(Reservation.class))).thenAnswer(call -> {
            Reservation reservation = call.getArgument(0);
            savedReservation.set(reservation);
            return reservation;
        });
        savedReservation.set(null);

        beginTransaction();
        service.reserve(showId, user, new ReserveRequest(List.of("A1"), "key"));
        TransactionSynchronizationUtils.triggerAfterCommit();
        TransactionSynchronizationManager.clearSynchronization();

        service.reserve(showId, user, new ReserveRequest(List.of("A1"), "key"));

        assertEquals(1.0, registry.get("reservations_confirmed_total").counter().count());
        assertEquals(
                1.0,
                registry.get("reservations_declined_total")
                        .tag("reason", "idempotent_replay")
                        .counter()
                        .count());
        assertEquals("IDEMPOTENT_REPLAY", events.latest(1).getFirst().eventType());
        assertEquals("RESERVATION_CONFIRMED", events.latest(2).get(1).eventType());
    }

    private final AtomicReference<Reservation> savedReservation = new AtomicReference<>();

    @Test
    void seatTakenAndPerUserLimitAreRecordedAtTheirDecisionPoints() {
        Seat taken = availableSeat("A1");
        taken.confirm(UUID.randomUUID(), Instant.now());
        when(reservations.findByUserIdAndShowIdAndIdempotencyKey(userId, showId, "taken"))
                .thenReturn(Optional.empty());
        when(reservations.findByUserIdAndShowIdAndIdempotencyKey(userId, showId, "limit"))
                .thenReturn(Optional.empty());
        when(seats.lockByShowIdAndLabels(showId, List.of("A1"))).thenReturn(List.of(taken));

        DomainException seatDecline = assertThrows(
                DomainException.class,
                () -> service.reserve(showId, user, new ReserveRequest(List.of("A1"), "taken")));
        assertEquals(DeclineReason.SEAT_TAKEN, seatDecline.getReason());

        Seat available = availableSeat("A2");
        when(seats.lockByShowIdAndLabels(showId, List.of("A2"))).thenReturn(List.of(available));
        when(seats.countActiveSeatsForUser(showId, userId)).thenReturn(4L);
        DomainException limitDecline = assertThrows(
                DomainException.class,
                () -> service.reserve(showId, user, new ReserveRequest(List.of("A2"), "limit")));
        assertEquals(DeclineReason.PER_USER_LIMIT, limitDecline.getReason());

        assertEquals(
                1.0,
                registry.get("reservations_declined_total")
                        .tag("reason", "seat_taken")
                        .counter()
                        .count());
        assertEquals(
                1.0,
                registry.get("reservations_declined_total")
                        .tag("reason", "per_user_limit")
                        .counter()
                        .count());
        assertEquals(List.of("PER_USER_LIMIT", "SEAT_TAKEN"),
                events.latest(2).stream().map(event -> event.eventType()).toList());
    }

    @Test
    void sameKeyWithDifferentSeatsRecordsConflict() {
        Reservation existing = new Reservation(
                UUID.randomUUID(), show, userId, 100L, ReservationStatus.CONFIRMED, "key",
                "different-hash", List.of("A2"), Instant.now(), null);
        when(reservations.findByUserIdAndShowIdAndIdempotencyKey(userId, showId, "key"))
                .thenReturn(Optional.of(existing));

        DomainException conflict = assertThrows(
                DomainException.class,
                () -> service.reserve(showId, user, new ReserveRequest(List.of("A1"), "key")));

        assertEquals(DeclineReason.IDEMPOTENCY_KEY_REUSED, conflict.getReason());
        assertEquals("IDEMPOTENCY_CONFLICT", events.latest(1).getFirst().eventType());
    }

    @Test
    void cancellationIsLoggedAfterCommit() {
        UUID reservationId = UUID.randomUUID();
        Seat seat = availableSeat("A1");
        seat.confirm(reservationId, Instant.now());
        Reservation reservation = new Reservation(
                reservationId, show, userId, 100L, ReservationStatus.CONFIRMED, "key",
                "hash", List.of("A1"), Instant.now(), null);
        when(reservations.findById(reservationId)).thenReturn(Optional.of(reservation));
        when(seats.lockByShowIdAndLabels(showId, List.of("A1"))).thenReturn(List.of(seat));
        when(seats.countByShowIdAndStatus(showId, SeatStatus.AVAILABLE)).thenReturn(9L);

        beginTransaction();
        service.cancel(reservationId, user);
        TransactionSynchronizationUtils.triggerAfterCommit();

        assertEquals("CANCELLATION", events.latest(1).getFirst().eventType());
        assertEquals(9.0, registry.get("seats_available").tag("show_id", showId.toString()).gauge().value());
    }

    private Seat availableSeat(String label) {
        return new Seat(UUID.randomUUID(), show, label, SeatStatus.AVAILABLE, Instant.now());
    }

    private void beginTransaction() {
        TransactionSynchronizationManager.initSynchronization();
    }
}
