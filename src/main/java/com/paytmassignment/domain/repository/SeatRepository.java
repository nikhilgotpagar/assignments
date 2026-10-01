package com.paytmassignment.domain.repository;

import com.paytmassignment.domain.model.Seat;
import com.paytmassignment.domain.model.SeatStatus;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SeatRepository extends JpaRepository<Seat, UUID> {

    List<Seat> findByShowIdOrderBySeatLabelAsc(UUID showId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT s FROM Seat s
            WHERE s.show.id = :showId AND s.seatLabel IN :labels
            ORDER BY s.seatLabel ASC
            """)
    List<Seat> lockByShowIdAndLabels(
            @Param("showId") UUID showId,
            @Param("labels") Collection<String> labels);

    @Query("""
            SELECT COUNT(s) FROM Seat s
            WHERE s.show.id = :showId
              AND s.status IN (com.paytmassignment.domain.model.SeatStatus.HELD,
                               com.paytmassignment.domain.model.SeatStatus.CONFIRMED)
              AND s.reservationId IN (
                  SELECT r.id FROM Reservation r
                  WHERE r.show.id = :showId
                    AND r.userId = :userId
                    AND r.status IN (com.paytmassignment.domain.model.ReservationStatus.HELD,
                                     com.paytmassignment.domain.model.ReservationStatus.CONFIRMED)
              )
            """)
    long countActiveSeatsForUser(@Param("showId") UUID showId, @Param("userId") UUID userId);

    long countByShowIdAndStatus(UUID showId, SeatStatus status);

    List<Seat> findByReservationId(UUID reservationId);
}
