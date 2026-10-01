package com.paytmassignment.api.filter;

import com.paytmassignment.api.auth.AuthContext;
import com.paytmassignment.application.exception.DomainException;
import com.paytmassignment.application.service.AuthService;
import com.paytmassignment.domain.model.User;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class AuthFilter extends OncePerRequestFilter {

    private static final Set<String> PUBLIC_PATHS = Set.of(
            "/auth/users", "/health/live", "/health/ready", "/livez", "/readyz", "/logs", "/actuator",
            "/prometheus");

    private final AuthService authService;

    public AuthFilter(AuthService authService) {
        this.authService = authService;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String path = request.getRequestURI();
        if ("POST".equalsIgnoreCase(request.getMethod()) && path.equals("/auth/users")) {
            return true;
        }
        if ("GET".equalsIgnoreCase(request.getMethod())) {
            if (PUBLIC_PATHS.contains(path) || path.startsWith("/actuator/")) {
                return true;
            }
            return path.matches("/shows/[^/]+");
        }
        return false;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        try {
            String header = request.getHeader("Authorization");
            String token = null;
            if (header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)) {
                token = header.substring(7).trim();
            }
            User user = authService.requireUserByToken(token);
            AuthContext.set(user);
            MDC.put("user_id", user.getId().toString());
            filterChain.doFilter(request, response);
        } catch (DomainException ex) {
            response.setStatus(ex.getHttpStatus());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            String requestId = MDC.get("request_id");
            String error = ex.getHttpStatus() == HttpServletResponse.SC_UNAUTHORIZED ? "unauthorized" : "forbidden";
            response.getWriter().write(
                    "{\"error\":\"" + error + "\",\"reason\":\"" + ex.getReason().name() + "\",\"message\":\""
                            + ex.getMessage()
                            + "\",\"request_id\":\""
                            + (requestId == null ? "" : requestId)
                            + "\"}");
        } finally {
            AuthContext.clear();
            MDC.remove("user_id");
        }
    }
}
