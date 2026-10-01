# Seat Reservation Service

Spring Boot JSON API with PostgreSQL-backed seat reservations.

## Run locally

Requirements: Docker with Compose.

Start the app and PostgreSQL:

```bash
ADMIN_TOKEN=token docker compose up --build -d
```

The API is at `http://localhost:8080`. Check that it is ready:

```bash
curl http://localhost:8080/health/ready
```

Run the concurrency and correctness checks:

```bash
ADMIN_TOKEN=token ./burst.sh http://localhost:8080
```

The burst creates test shows and users that remain in the database. It defaults to 500 hot-seat requests; if your machine has process limits, reduce the concurrency, for example `HOT_USERS=50 LIMIT_USERS=10`. Stop the local services with `docker compose down`.

## Run against Render

Live service: https://paytm-seat-reservation-qiq4.onrender.com

Set the Render service's `ADMIN_TOKEN` environment variable to `token` for this assignment demo. In your terminal, set the same value and run the burst:

```bash
export ADMIN_TOKEN=token
./burst.sh https://paytm-seat-reservation-qiq4.onrender.com
```

The burst creates persistent test data in the Render database. Use the default 500 hot-seat requests only if the machine running the script can support that many concurrent workers; otherwise reduce `HOT_USERS`.

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

| Method | Endpoint | Purpose |
|---|---|---|
| `POST` | `/auth/users` | Create a user and receive a bearer token |
| `POST` | `/shows` | Create a show (admin token required) |
| `GET` | `/shows/{id}` | Read seat states and counts |
| `POST` | `/shows/{id}/reserve` | Reserve seats (user token required) |
| `POST` | `/reservations/{id}/cancel` | Cancel your reservation |
| `GET` | `/health/live` | Liveness |

Reserve requests are all-or-nothing. Identity and cancellation ownership come from the bearer token. For the locking, idempotency, and consistency design, see [WRITEUP.md](WRITEUP.md).
