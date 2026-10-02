# Seat Reservation Service

Spring Boot JSON API with PostgreSQL-backed seat reservations.

## Run locally

Requirements: Docker with Compose.

1. Start the app and PostgreSQL:

```bash
ADMIN_TOKEN=token docker compose up --build -d
```

2. Check readiness:

```bash
curl http://localhost:8080/health/ready
```

3. Run the concurrency and correctness checks:

```bash
bash ./burst.sh http://localhost:8080
```

The API is available at `http://localhost:8080`. The burst creates test shows and users that remain in the database. It defaults to 500 concurrent hot-seat requests. If your machine has connection or thread limits, reduce the concurrency, for example `HOT_USERS=50 HOT_PARALLELISM=50 LIMIT_USERS=10`. Stop the local services with `docker compose down`.

## Run against Render

Live service: https://paytm-seat-reservation-qiq4.onrender.com

1. Set the Render service's `ADMIN_TOKEN` environment variable to `token`.
2. From your project directory, run:

```bash
bash ./burst.sh https://paytm-seat-reservation-qiq4.onrender.com
```

The burst creates persistent test data in the Render database. Use the default 500 concurrent requests only if the machine running the script can support them; otherwise reduce `HOT_USERS` and `HOT_PARALLELISM`.

## Metrics and logs

| Purpose | Local | Live |
|---|---|---|
| Readiness (checks PostgreSQL) | `http://localhost:8080/health/ready` | https://paytm-seat-reservation-qiq4.onrender.com/health/ready |
| Prometheus metrics | `http://localhost:8080/actuator/prometheus` | https://paytm-seat-reservation-qiq4.onrender.com/actuator/prometheus |
| Recent business events | `http://localhost:8080/logs` | https://paytm-seat-reservation-qiq4.onrender.com/logs |

Metrics include confirmed reservations, declines by reason, and available seats per show. `/logs` returns up to 500 recent sanitized reservation events; use `?limit=100` to choose a smaller result.

Each event includes its timestamp, request ID, type, show/user/reservation IDs when available, seats, and HTTP status. To inspect the public event feed:

```bash
curl 'http://localhost:8080/logs?limit=100'
curl 'https://paytm-seat-reservation-qiq4.onrender.com/logs?limit=100'
```

For local structured application logs, follow the app container output:

```bash
docker compose logs -f app
```

The app writes structured reservation events to stdout, including the request ID. Render captures stdout/stderr in its service logs, which are viewed in the Render dashboard; `/logs` is the public, sanitized business-event feed and is not a raw log viewer. The `/logs` buffer is in memory, holds at most 500 events, and resets when the app restarts.

## API

| Method | Endpoint | Purpose | Request body |
|---|---|---|---|
| `POST` | `/auth/users` | Create a user and receive a bearer token | `{"display_name":"Alice"}` |
| `POST` | `/shows` | Create a show (admin bearer token required) | `{"name":"friday-night","seats":["A1","A2"],"price_paise":25000}` |
| `GET` | `/shows/{id}` | Read seat states and counts | None |
| `POST` | `/shows/{id}/reserve` | Reserve seats (user bearer token required) | `{"seats":["A1"],"idempotency_key":"alice-friday-a1"}` |
| `POST` | `/reservations/{id}/cancel` | Cancel your reservation (owner bearer token required) | None |
| `GET` | `/health/live` | Liveness | None |

Example requests (use the live base URL or replace it with `http://localhost:8080`):

```bash
BASE_URL=https://paytm-seat-reservation-qiq4.onrender.com

# Create a user; save the returned token for authenticated requests.
curl -X POST "$BASE_URL/auth/users" \
  -H 'Content-Type: application/json' \
  -d '{"display_name":"Alice"}'

# Create a show with the admin token; note the returned show ID.
curl -X POST "$BASE_URL/shows" \
  -H 'Authorization: Bearer token' \
  -H 'Content-Type: application/json' \
  -d '{"name":"friday-night","seats":["A1","A2"],"price_paise":25000}'

# Reserve using the user token returned by /auth/users.
curl -X POST "$BASE_URL/shows/SHOW_ID/reserve" \
  -H 'Authorization: Bearer USER_TOKEN' \
  -H 'Content-Type: application/json' \
  -d '{"seats":["A1"],"idempotency_key":"alice-friday-a1"}'

# Cancel using the same owner's user token. This endpoint has no body.
curl -X POST "$BASE_URL/reservations/RESERVATION_ID/cancel" \
  -H 'Authorization: Bearer USER_TOKEN'
```

Reserve requests are all-or-nothing. Identity and cancellation ownership come from the bearer token. For the locking, idempotency, and consistency design, see [WRITEUP.md](WRITEUP.md).
