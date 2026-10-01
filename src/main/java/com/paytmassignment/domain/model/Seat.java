package com.paytmassignment.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "seats")
public class Seat {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "show_id", nullable = false)
    private Show show;

    @Column(name = "seat_label", nullable = false, length = 32)
    private String seatLabel;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private SeatStatus status;

    @Column(name = "reservation_id")
    private UUID reservationId;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Seat() {
    }

    public Seat(UUID id, Show show, String seatLabel, SeatStatus status, Instant updatedAt) {
        this.id = id;
        this.show = show;
        this.seatLabel = seatLabel;
        this.status = status;
        this.updatedAt = updatedAt;
    }

    public UUID getId() {
        return id;
    }

    public Show getShow() {
        return show;
    }

    public String getSeatLabel() {
        return seatLabel;
    }

    public SeatStatus getStatus() {
        return status;
    }

    public UUID getReservationId() {
        return reservationId;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public boolean isAvailable() {
        return status == SeatStatus.AVAILABLE;
    }

    public void hold(UUID reservationId, Instant at) {
        if (status != SeatStatus.AVAILABLE) {
            throw new IllegalStateException("Seat " + seatLabel + " is not available");
        }
        this.status = SeatStatus.HELD;
        this.reservationId = reservationId;
        this.updatedAt = at;
    }

    public void confirm(UUID reservationId, Instant at) {
        if (status != SeatStatus.AVAILABLE && status != SeatStatus.HELD) {
            throw new IllegalStateException("Seat " + seatLabel + " cannot be confirmed from " + status);
        }
        if (status == SeatStatus.HELD && this.reservationId != null && !this.reservationId.equals(reservationId)) {
            throw new IllegalStateException("Seat " + seatLabel + " held by another reservation");
        }
        this.status = SeatStatus.CONFIRMED;
        this.reservationId = reservationId;
        this.updatedAt = at;
    }

    public void release(Instant at) {
        this.status = SeatStatus.AVAILABLE;
        this.reservationId = null;
        this.updatedAt = at;
    }
}
