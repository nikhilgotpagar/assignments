package com.paytmassignment.application.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

public record ReserveRequest(
        @NotEmpty @Size(max = 100) List<@NotBlank @Size(max = 32) String> seats,
        @NotBlank @Size(max = 128) String idempotency_key) {
}
