package com.paytmassignment.api.controller;

import com.paytmassignment.api.auth.AuthContext;
import com.paytmassignment.application.dto.request.CreateShowRequest;
import com.paytmassignment.application.dto.request.ReserveRequest;
import com.paytmassignment.application.dto.response.ReservationResponse;
import com.paytmassignment.application.dto.response.ShowCreatedResponse;
import com.paytmassignment.application.dto.response.ShowResponse;
import com.paytmassignment.application.exception.DomainException;
import com.paytmassignment.application.service.ReservationService;
import com.paytmassignment.application.service.ShowService;
import com.paytmassignment.domain.model.User;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/shows")
public class ShowController {

    private final ShowService showService;
    private final ReservationService reservationService;

    public ShowController(ShowService showService, ReservationService reservationService) {
        this.showService = showService;
        this.reservationService = reservationService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ShowCreatedResponse createShow(@Valid @RequestBody CreateShowRequest request) {
        User user = AuthContext.require();
        if (!user.isAdmin()) {
            throw DomainException.forbidden("Admin token required to create shows");
        }
        return showService.createShow(request);
    }

    @GetMapping("/{id}")
    public ShowResponse getShow(@PathVariable("id") UUID id) {
        return showService.getShow(id);
    }

    @PostMapping("/{id}/reserve")
    @ResponseStatus(HttpStatus.CREATED)
    public ReservationResponse reserve(
            @PathVariable("id") UUID id, @Valid @RequestBody ReserveRequest request) {
        return reservationService.reserve(id, AuthContext.require(), request);
    }
}
