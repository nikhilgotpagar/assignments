package com.paytmassignment.application.dto.response;

import java.util.List;
import java.util.UUID;

public record UserResponse(UUID user_id, String display_name, String token, String role) {
}
