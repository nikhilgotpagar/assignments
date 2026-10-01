package com.paytmassignment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import tools.jackson.databind.ObjectMapper;
import com.paytmassignment.application.dto.request.CreateShowRequest;
import com.paytmassignment.application.dto.request.CreateUserRequest;
import com.paytmassignment.application.dto.request.ReserveRequest;
import com.paytmassignment.application.dto.response.ReservationResponse;
import com.paytmassignment.application.dto.response.ShowCreatedResponse;
import com.paytmassignment.application.dto.response.ShowResponse;
import com.paytmassignment.application.dto.response.UserResponse;
import com.paytmassignment.application.exception.DomainException;
import com.paytmassignment.application.service.AuthService;
import com.paytmassignment.application.service.ReservationService;
import com.paytmassignment.application.service.ShowService;
import com.paytmassignment.domain.model.User;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class PaytmAssignmentApplicationTests {

    @Autowired
    private AuthService authService;

    @Autowired
    private ShowService showService;

    @Autowired
    private ReservationService reservationService;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Value("${app.admin-token}")
    private String adminToken;

    @Test
    void hotSeatHasExactlyOneWinnerUnderConcurrentRequests() throws Exception {
        ShowCreatedResponse show = createShow(List.of("A12"), 4);
        List<User> users = createUsers(50);

        List<Object> results = concurrently(50, index -> reservationService.reserve(
                show.id(), users.get(index), request("A12", "hot-" + index)));

        assertEquals(1, results.stream().filter(ReservationResponse.class::isInstance).count());
        assertEquals(49, conflictCount(results));
        assertReconciles(show.id(), 1);
    }

    @Test
    void concurrentReservationsRespectPerUserLimit() throws Exception {
        ShowCreatedResponse show = createShow(
                java.util.stream.IntStream.range(0, 10).mapToObj(i -> "A" + i).toList(), 4);
        User user = createUsers(1).getFirst();

        List<Object> results = concurrently(
                10, index -> reservationService.reserve(show.id(), user, request("A" + index, "limit-" + index)));

        assertEquals(4, results.stream().filter(ReservationResponse.class::isInstance).count());
        assertEquals(6, conflictCount(results));
        assertReconciles(show.id(), 4);
    }

    @Test
    void concurrentSameKeyRequestsReplayOneReservationAndRejectDifferentBody() throws Exception {
        ShowCreatedResponse show = createShow(List.of("A1", "A2"), 4);
        User user = createUsers(1).getFirst();

        List<Object> results =
                concurrently(12, index -> reservationService.reserve(show.id(), user, request("A1", "same-key")));

        List<ReservationResponse> reservations =
                results.stream().map(ReservationResponse.class::cast).toList();
        UUID reservationId = reservations.getFirst().reservation_id();
        assertEquals(1, reservations.stream().map(ReservationResponse::reservation_id).distinct().count());
        assertEquals(12, reservations.stream().map(ReservationResponse::reservation_id).filter(reservationId::equals).count());

        DomainException reusedKey = org.junit.jupiter.api.Assertions.assertThrows(
                DomainException.class,
                () -> reservationService.reserve(show.id(), user, request("A2", "same-key")));
        assertEquals(409, reusedKey.getHttpStatus());
        assertReconciles(show.id(), 1);
    }

    @Test
    void cancellationIsOwnerOnlyAndReleasesSeatForRebooking() {
        ShowCreatedResponse show = createShow(List.of("A1"), 4);
        User owner = createUsers(1).getFirst();
        User other = createUsers(1).getFirst();
        ReservationResponse reservation = reservationService.reserve(show.id(), owner, request("A1", "first"));

        DomainException forbidden = org.junit.jupiter.api.Assertions.assertThrows(
                DomainException.class, () -> reservationService.cancel(reservation.reservation_id(), other));
        assertEquals(403, forbidden.getHttpStatus());

        reservationService.cancel(reservation.reservation_id(), owner);
        ReservationResponse rebooked = reservationService.reserve(show.id(), other, request("A1", "second"));
        assertNotNull(rebooked.reservation_id());
        assertReconciles(show.id(), 1);
    }

    @Test
    void concurrentOverlappingMultiSeatRequestsAreAllOrNothing() throws Exception {
        ShowCreatedResponse show = createShow(List.of("A1", "A2"), 4);
        List<User> users = createUsers(2);

        List<Object> results = concurrently(2, index -> reservationService.reserve(
                show.id(),
                users.get(index),
                new ReserveRequest(index == 0 ? List.of("A1", "A2") : List.of("A2", "A1"), "pair-" + index)));

        assertEquals(1, results.stream().filter(ReservationResponse.class::isInstance).count());
        assertEquals(1, conflictCount(results));
        assertReconciles(show.id(), 2);
    }

    @Test
    void httpHealthMetricsIdentityAndDomainDeclinesWork() throws Exception {
        ShowCreatedResponse show = createShow(List.of("A1"), 4);
        User first = createUsers(1).getFirst();
        User second = createUsers(1).getFirst();
        String firstToken = first.getToken();

        mockMvc.perform(get("/health/live"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
        mockMvc.perform(get("/health/ready"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.db").value("up"));

        String requestBody = objectMapper.writeValueAsString(
                java.util.Map.of("seats", List.of("A1"), "idempotency_key", "http-first", "user_id", second.getId()));
        String response = mockMvc.perform(post("/shows/{id}/reserve", show.id())
                        .header("Authorization", "Bearer " + firstToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.user_id").value(first.getId().toString()))
                .andExpect(jsonPath("$.reservation_id").exists())
                .andReturn()
                .getResponse()
                .getContentAsString();
        UUID reservationId = UUID.fromString(objectMapper.readTree(response).get("reservation_id").asText());

        mockMvc.perform(post("/shows/{id}/reserve", show.id())
                        .header("Authorization", "Bearer " + second.getToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request("A1", "http-second"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value("SEAT_TAKEN"));

        mockMvc.perform(post("/reservations/{id}/cancel", reservationId)
                        .header("Authorization", "Bearer " + second.getToken()))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .string(Matchers.allOf(
                                Matchers.containsString("reservations_confirmed_total"),
                                Matchers.containsString("reservations_declined_total"),
                                Matchers.containsString("seats_available"))));

        ShowResponse state = showService.getShow(show.id());
        assertEquals(state.total_seats(), state.available() + state.held() + state.confirmed());
    }

    @Test
    void invalidReservationIsAClientErrorNotServerError() throws Exception {
        ShowCreatedResponse show = createShow(List.of("A1"), 4);
        User user = createUsers(1).getFirst();

        mockMvc.perform(post("/shows/{id}/reserve", show.id())
                        .header("Authorization", "Bearer " + user.getToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"seats\":[],\"idempotency_key\":\"invalid\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason").value("VALIDATION"));
    }

    private ShowCreatedResponse createShow(List<String> seats, int limit) {
        return showService.createShow(new CreateShowRequest(
                "test-" + UUID.randomUUID(), seats, 25000L, limit));
    }

    private List<User> createUsers(int count) {
        List<User> users = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            UserResponse response = authService.createUser(new CreateUserRequest("test-user-" + UUID.randomUUID()));
            users.add(authService.requireUserByToken(response.token()));
        }
        return users;
    }

    private static ReserveRequest request(String seat, String key) {
        return new ReserveRequest(List.of(seat), key);
    }

    private void assertReconciles(UUID showId, int expectedConfirmed) {
        ShowResponse state = showService.getShow(showId);
        assertEquals(state.total_seats(), state.available() + state.held() + state.confirmed());
        assertEquals(expectedConfirmed, state.confirmed());
    }

    private static long conflictCount(List<Object> results) {
        return results.stream()
                .filter(DomainException.class::isInstance)
                .map(DomainException.class::cast)
                .peek(exception -> assertEquals(409, exception.getHttpStatus()))
                .count();
    }

    private static List<Object> concurrently(int count, IndexedCall call) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(count);
        CountDownLatch ready = new CountDownLatch(count);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Object>> futures = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                int index = i;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(30, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Timed out waiting for concurrent test start");
                    }
                    try {
                        return call.run(index);
                    } catch (DomainException expectedDecline) {
                        return expectedDecline;
                    }
                }));
            }
            if (!ready.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Concurrent test workers did not become ready");
            }
            start.countDown();
            List<Object> results = new ArrayList<>(count);
            for (Future<Object> future : futures) {
                results.add(future.get(Duration.ofSeconds(90).toMillis(), TimeUnit.MILLISECONDS));
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }

    @FunctionalInterface
    private interface IndexedCall {
        Object run(int index);
    }
}
