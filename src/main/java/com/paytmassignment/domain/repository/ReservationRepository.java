package com.paytmassignment.domain.repository;

import com.paytmassignment.domain.model.Reservation;
import com.paytmassignment.domain.model.ReservationStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReservationRepository extends JpaRepository<Reservation, UUID> {

    Optional<Reservation> findByUserIdAndShowIdAndIdempotencyKey(UUID userId, UUID showId, String idempotencyKey);

    @Query("""
            SELECT r FROM Reservation r
            WHERE r.status = com.paytmassignment.domain.model.ReservationStatus.HELD
              AND r.expiresAt IS NOT NULL
              AND r.expiresAt < :now
            ORDER BY r.expiresAt
            """)
    List<Reservation> findExpiredHolds(@Param("now") Instant now);

    long countByShowIdAndStatus(UUID showId, ReservationStatus status);
}
