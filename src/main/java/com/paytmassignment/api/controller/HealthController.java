package com.paytmassignment.api.controller;

import java.util.Map;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {

    private final DataSource dataSource;

    public HealthController(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @GetMapping({"/health/live", "/livez"})
    public Map<String, String> live() {
        return Map.of("status", "UP");
    }

    @GetMapping({"/health/ready", "/readyz"})
    public ResponseEntity<Map<String, String>> ready() {
        try (var connection = dataSource.getConnection()) {
            if (!connection.isValid(2)) {
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                        .body(Map.of("status", "DOWN", "db", "unreachable"));
            }
            return ResponseEntity.ok(Map.of("status", "UP", "db", "up"));
        } catch (SQLException ex) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("status", "DOWN", "db", "unreachable"));
        }
    }
}
