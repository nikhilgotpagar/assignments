package com.paytmassignment.infrastructure.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.paytmassignment.api.controller.LogsController;
import com.paytmassignment.application.exception.DomainException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

class ReservationEventBufferTest {

    @Test
    void emptyEndpointReturnsAnEmptyListAndRejectsOutOfRangeLimits() {
        LogsController controller = new LogsController(new ReservationEventBuffer());

        assertTrue(controller.getLogs(100).isEmpty());
        assertThrows(DomainException.class, () -> controller.getLogs(0));
        assertThrows(DomainException.class, () -> controller.getLogs(501));
    }

    @Test
    void bufferKeepsNewestEventsFirstAndEvictsOldest() {
        ReservationEventBuffer buffer = new ReservationEventBuffer();
        for (int i = 0; i <= ReservationEventBuffer.CAPACITY; i++) {
            buffer.record("TEST_" + i, null, null, null, List.of("A1"), 201);
        }

        List<ReservationBusinessEvent> recent = buffer.latest(2);
        assertEquals(2, recent.size());
        assertEquals("TEST_500", recent.get(0).eventType());
        assertEquals("TEST_499", recent.get(1).eventType());
        assertEquals(ReservationEventBuffer.CAPACITY, buffer.latest(500).size());
        assertFalse(recent.getFirst().requestId().isBlank());
    }

    @Test
    void concurrentWritersKeepTheBufferBoundedAndEventsComplete() throws Exception {
        ReservationEventBuffer buffer = new ReservationEventBuffer();
        ExecutorService executor = Executors.newFixedThreadPool(16);
        try {
            List<java.util.concurrent.Future<?>> writes = new java.util.ArrayList<>();
            for (int worker = 0; worker < 16; worker++) {
                int workerId = worker;
                writes.add(executor.submit(() -> {
                    for (int i = 0; i < 100; i++) {
                        buffer.record("RESERVATION_CONFIRMED", UUID.randomUUID(), UUID.randomUUID(),
                                UUID.randomUUID(), List.of("A" + workerId), 201);
                    }
                }));
            }
            for (var write : writes) {
                write.get();
            }
        } finally {
            executor.shutdownNow();
        }

        List<ReservationBusinessEvent> events = buffer.latest(ReservationEventBuffer.CAPACITY);
        assertEquals(ReservationEventBuffer.CAPACITY, events.size());
        assertTrue(events.stream().allMatch(event ->
                !event.requestId().isBlank()
                        && event.showId() != null
                        && event.userId() != null
                        && event.reservationId() != null
                        && event.eventType().equals("RESERVATION_CONFIRMED")));
        assertEquals(
                List.of("timestamp", "requestId", "eventType", "showId", "userId", "reservationId", "seats", "httpStatus"),
                java.util.Arrays.stream(ReservationBusinessEvent.class.getRecordComponents())
                        .map(java.lang.reflect.RecordComponent::getName)
                        .toList());
    }
}
