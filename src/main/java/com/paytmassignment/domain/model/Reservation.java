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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "reservations")
public class Reservation {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "show_id", nullable = false)
    private Show show;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "amount_paise", nullable = false)
    private long amountPaise;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ReservationStatus status;

    @Column(name = "idempotency_key", nullable = false, length = 128)
    private String idempotencyKey;

    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Column(name = "seat_labels_json", nullable = false, length = 4000)
    private String seatLabelsJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    protected Reservation() {
    }

    public Reservation(
            UUID id,
            Show show,
            UUID userId,
            long amountPaise,
            ReservationStatus status,
            String idempotencyKey,
            String requestHash,
            List<String> seatLabels,
            Instant createdAt,
            Instant expiresAt) {
        this.id = id;
        this.show = show;
        this.userId = userId;
        this.amountPaise = amountPaise;
        this.status = status;
        this.idempotencyKey = idempotencyKey;
        this.requestHash = requestHash;
        setSeatLabels(seatLabels);
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public UUID getId() {
        return id;
    }

    public Show getShow() {
        return show;
    }

    public UUID getUserId() {
        return userId;
    }

    public long getAmountPaise() {
        return amountPaise;
    }

    public ReservationStatus getStatus() {
        return status;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public List<String> getSeatLabels() {
        if (seatLabelsJson == null || seatLabelsJson.isBlank()) {
            return List.of();
        }
        String trimmed = seatLabelsJson.trim();
        if (trimmed.equals("[]")) {
            return List.of();
        }
        String inner = trimmed.substring(1, trimmed.length() - 1);
        if (inner.isBlank()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String part : inner.split(",")) {
            String s = part.trim();
            if (s.startsWith("\"") && s.endsWith("\"") && s.length() >= 2) {
                s = s.substring(1, s.length() - 1);
            }
            result.add(s);
        }
        return List.copyOf(result);
    }

    public void setSeatLabels(List<String> seatLabels) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < seatLabels.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(seatLabels.get(i)).append('"');
        }
        sb.append(']');
        this.seatLabelsJson = sb.toString();
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getCancelledAt() {
        return cancelledAt;
    }

    public boolean isActive() {
        return status == ReservationStatus.HELD || status == ReservationStatus.CONFIRMED;
    }

    public void cancel(Instant at) {
        if (!isActive()) {
            throw new IllegalStateException("Reservation is not active");
        }
        this.status = ReservationStatus.CANCELLED;
        this.cancelledAt = at;
        this.expiresAt = null;
    }

    public void expire(Instant at) {
        if (status != ReservationStatus.HELD) {
            throw new IllegalStateException("Only held reservations can expire");
        }
        this.status = ReservationStatus.EXPIRED;
        this.cancelledAt = at;
        this.expiresAt = null;
    }
}
