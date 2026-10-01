# WRITEUP

## Atomic decision

The seat assignment decision is a single database transaction in `ReservationService.reserve`:

1. **PostgreSQL pessimistic write lock on the existing user row** — serializes one user's concurrent reserve calls so the per-user seat limit cannot be raced. Using an existing row avoids a race while creating a separate lock record.
2. **Pessimistic lock on requested seats `ORDER BY seat_label`** (`LockModeType.PESSIMISTIC_WRITE`) — takes row locks on exactly the requested seats in a **global lexicographic order**.
3. In that locked snapshot: reject if any seat is not `AVAILABLE` (all-or-nothing); reject if `active_seats + requested > per_user_limit`; otherwise mark seats `CONFIRMED` and insert the reservation.

Why this is race-free: two transactions cannot both observe the same seat as `AVAILABLE` under a pessimistic write lock. The second waiter sees `CONFIRMED`/`HELD` and returns **409**, never a second confirmation and never a 500.

### Multi-seat / deadlock avoidance

Every reserve sorts seat labels before locking. All transactions acquire seat locks in the same total order, so the classic A1↔A2 lock-order deadlock cannot occur. Partial success is not used: if any seat in the request is unavailable, the whole request declines with `SEAT_TAKEN` (all-or-nothing). Documented behaviour; holds under concurrency because the check happens while holding locks on the full set.

## Idempotency

- Stored in PostgreSQL on `reservations` as `(user_id, show_id, idempotency_key)` with a **UNIQUE** constraint, plus a `request_hash` (SHA-256 of the normalized sorted seat list).
- Same key + same body → return the original reservation (counted as `idempotent_replay` in metrics; HTTP 201).
- Same key + different seats → **409** `IDEMPOTENCY_KEY_REUSED`.
- Concurrent same-key requests for one user serialize on the user-row lock. After waiting, the second transaction rechecks the unique-key lookup and replays the committed reservation. The unique constraint remains the durable database guard.

## Holds & expiry

Model chosen: **immediate confirmation + explicit cancel** (`POST /reservations/{id}/cancel`).

- Reserve → seats `confirmed`, reservation `confirmed` (matches the assignment success shape).
- Cancel (owner only) → seats returned to `available`. A cancel never resurrects a seat already bound to a different `reservation_id` (release is gated on matching reservation id under row locks).
- Schema still supports `HELD` / `expires_at` for a future soft-hold TTL without a migration rewrite.

## Consistency vs availability under partition

The application and tests use PostgreSQL as the only database. If PostgreSQL is unreachable, readiness fails closed (`/health/ready` → 503) and writes fail rather than using stale seat data. Clients should retry idempotently after connectivity returns.

## Observability (2am page)

You would get paged for:

- Readiness flapping / PostgreSQL connection errors
- Any rise in HTTP 5xx (domain declines must stay 4xx)
- `available + held + confirmed != total_seats` (invariant; currently asserted on read)
- Confirmed counter diverging from `confirmed` seat count after a burst
- Saturation: Hikari pool wait, elevated reserve latency, lock wait spikes

Structured logs include `request_id` (`X-Request-Id`) and `user_id` in MDC. Metrics: `reservations_confirmed_total`, `reservations_declined_total{reason}`, `seats_available{show_id}`.

## AI usage

- **Directed:** scaffolding Spring Boot layout, Dockerfile/Compose, Prometheus wiring, burst script structure, README/WRITEUP drafting.
- **Decided by me:** atomic locking design (existing user row + seat pessimistic locks in label order), all-or-nothing multi-seat, immediate-confirm + cancel model, idempotency schema, package boundaries (api / application / domain / infrastructure), and the correctness bar the burst script asserts.

## What I'd do next

- Soft-hold TTL + payment confirm step; outbox for downstream tickets
- Connection-pool / statement timeouts tuned from burst profiles
- OpenTelemetry traces on the reserve path
- Property-based / Jepsen-style concurrency suite in CI with Testcontainers
