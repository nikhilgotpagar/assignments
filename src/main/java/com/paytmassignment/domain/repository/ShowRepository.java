package com.paytmassignment.domain.repository;

import com.paytmassignment.domain.model.Show;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ShowRepository extends JpaRepository<Show, UUID> {
}
