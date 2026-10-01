# Seat Reservation Design

## Atomic decision

PostgreSQL is the source of truth. `ReservationService.reserve` runs in one database transaction and makes the reservation decision while holding database row locks:

1. It first checks for a prior reservation with the same user, show, and idempotency key.
2. It obtains a `PESSIMISTIC_WRITE` lock on the authenticated user's existing row. This serializes that user's reservation attempts, including attempts using different keys, so two concurrent requests cannot both pass the per-user limit check.
3. It locks all requested seat rows for the show using `PESSIMISTIC_WRITE`, ordered by `seat_label`.
4. While those locks are held, it rejects the whole request if any seat is not `AVAILABLE` or if the user's active seat count plus the requested count exceeds the limit. Otherwise it inserts the reservation and marks every requested seat `CONFIRMED`. Both changes commit together.

For a hot seat, transactions queue on that seat's database row. The first transaction to commit changes the state; a waiter then observes the non-available state and receives a 409 rather than creating another reservation. Database transaction rollback keeps the reservation and seat state from being partially committed.

### Multi-seat requests and deadlocks

Requests are normalized, deduplicated, and sorted by seat label before locking. All reserve and cancel operations acquire seat locks in that same order, which avoids cycles caused by transactions locking the same seats in opposite orders. The request is all-or-nothing: if any requested seat is missing or unavailable, none of the requested seats are reserved.

## Idempotency

The key and request hash are stored on the PostgreSQL `reservations` row. A database unique constraint on `(user_id, show_id, idempotency_key)` is the durable duplicate guard. The application trims the key and computes a SHA-256 hash over the normalized, sorted seat list.

- Same user, show, key, and normalized seat set: return the existing reservation; do not create another reservation or increment the confirmed-reservations counter.
- Same user, show, and key but a different normalized seat set: return 409 (`IDEMPOTENCY_KEY_REUSED`).
- Concurrent attempts by the same user: the user-row lock serializes the operations, and the key lookup is repeated after acquiring that lock. The unique constraint remains the final database safeguard.

This covers a client retry after a committed reservation whose response was lost: the retry receives the original reservation. There is no payment processor in this service, so no payment is charged; if payment is added, it will need its own idempotency key and transactional handoff.

## Holds and expiry

The active model is immediate confirmation with explicit owner-only cancellation. A successful reserve changes seats directly from `AVAILABLE` to `CONFIRMED`; it does not create a time-limited hold. Cancellation changes the reservation to `CANCELLED` and releases only seat rows whose `reservation_id` still matches that reservation, under seat-row locks. This prevents an old cancellation from releasing a seat assigned to another reservation.

The schema and domain model have `HELD` and expiry fields for a possible future hold workflow, but there is no automatic hold expiry in the current API.

## Consistency vs. availability under a partition

PostgreSQL transactions and row locks coordinate all application instances. If the application cannot reach PostgreSQL, readiness fails (`/health/ready` reports unavailable) and reservation writes fail; the service does not make decisions from a local or stale seat copy. This favors consistency over availability during a database partition. Clients can retry after recovery using the same idempotency key.

## Observability and 2am alerts

The public `/actuator/prometheus` endpoint exposes confirmed-reservation and decline counters, including the decline reason, plus a `seats_available{show_id=...}` gauge derived from database seat state. The gauge is refreshed after committed seat changes and on startup; it is not an independently incremented inventory counter. `/logs` returns the newest sanitized business events from a thread-safe in-memory buffer capped at 500 entries. Structured reservation events are also written to standard application logs with the request ID. `X-Request-Id` is generated per request.

At 2am, I would want alerts for:

- Readiness failures, database connectivity errors, or connection-pool exhaustion.
- Unexpected HTTP 5xx responses or elevated reservation latency and database lock waits. Seat-taken and limit outcomes are expected 409 domain responses, not server errors.
- Any reconciliation mismatch between the show totals and its available, held, and confirmed seat counts.
- A mismatch between committed reservation records and seat assignments, or an unexpected increase in duplicate-key / idempotency conflicts.

Metrics and the event buffer are in-process observability, not durable audit storage: counters and recent logs reset on restart, and metrics from multiple app instances are instance-local. PostgreSQL remains authoritative for reservation and seat state. The current project exposes metrics and logs but does not provision an external alerting service or durable log store.

## AI usage

**Directed by me:** the assignment requirements and correctness bar, the constraint to work within the existing Spring Boot project, and the request for public metrics and sanitized business logs without unrelated rewrites.

**AI-assisted decisions and work:** AI inspected the existing code, proposed and implemented the specific concurrency/idempotency approach described above, and helped revise the configuration, tests, and documentation. I directed the required outcomes, but I did not independently author every implementation detail or make every low-level design choice; the concrete mechanism documented here is the one implemented in the project.

## What I would do next

1. Add integration tests against real PostgreSQL that race hot-seat, same-key, per-user-limit, and cancellation requests; run them repeatedly in CI.
2. If adding payment, introduce a payment-provider idempotency key and an outbox so database commits and external side effects can be reconciled safely.
3. Add durable centralized logs, distributed traces, and configured alerts/dashboards. Retain the current `/logs` endpoint as a bounded evaluator/debugging view, not an audit trail.
4. Profile the deployed burst and tune database connection-pool size, lock/query timeouts, and request limits from measured behavior.
