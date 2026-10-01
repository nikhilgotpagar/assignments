package com.paytmassignment.application.service;

import com.paytmassignment.application.dto.request.CreateUserRequest;
import com.paytmassignment.application.dto.response.UserResponse;
import com.paytmassignment.application.exception.DomainException;
import com.paytmassignment.config.AppProperties;
import com.paytmassignment.domain.model.User;
import com.paytmassignment.domain.model.UserRole;
import com.paytmassignment.domain.repository.UserRepository;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService implements ApplicationRunner {

    private final UserRepository userRepository;
    private final AppProperties appProperties;
    private final SecureRandom secureRandom = new SecureRandom();

    public AuthService(UserRepository userRepository, AppProperties appProperties) {
        this.userRepository = userRepository;
        this.appProperties = appProperties;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        String adminToken = appProperties.adminToken();
        userRepository.findByToken(adminToken).orElseGet(() -> {
            User admin = new User(UUID.randomUUID(), adminToken, "admin", UserRole.ADMIN, Instant.now());
            return userRepository.save(admin);
        });
    }

    @Transactional
    public UserResponse createUser(CreateUserRequest request) {
        String token = generateToken();
        User user = new User(UUID.randomUUID(), token, request.display_name().trim(), UserRole.USER, Instant.now());
        userRepository.save(user);
        return toResponse(user);
    }

    @Transactional(readOnly = true)
    public User requireUserByToken(String token) {
        if (token == null || token.isBlank()) {
            throw DomainException.forbidden("Missing bearer token");
        }
        return userRepository.findByToken(token)
                .orElseThrow(() -> DomainException.forbidden("Invalid bearer token"));
    }

    private String generateToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private static UserResponse toResponse(User user) {
        return new UserResponse(user.getId(), user.getDisplayName(), user.getToken(), user.getRole().name());
    }
}
