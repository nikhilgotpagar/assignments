package com.paytmassignment.application.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

public record CreateShowRequest(
        @NotBlank @Size(max = 256)
        String name,
        @NotEmpty @Size(max = 10_000)
        List<@NotBlank @Size(max = 32) String> seats,
        @NotNull @Min(0)
        Long price_paise,
        @Min(1)
        Integer per_user_limit) {
}
