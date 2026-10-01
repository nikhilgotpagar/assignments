package com.paytmassignment.api.controller;

import com.paytmassignment.api.auth.AuthContext;
import com.paytmassignment.application.dto.response.ReservationResponse;
import com.paytmassignment.application.service.ReservationService;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/reservations")
public class ReservationController {

    private final ReservationService reservationService;

    public ReservationController(ReservationService reservationService) {
        this.reservationService = reservationService;
    }

    @GetMapping("/{id}")
    public ReservationResponse get(@PathVariable("id") UUID id) {
        return reservationService.getReservation(id, AuthContext.require());
    }

    @PostMapping("/{id}/cancel")
    public ReservationResponse cancel(@PathVariable("id") UUID id) {
        return reservationService.cancel(id, AuthContext.require());
    }
}
