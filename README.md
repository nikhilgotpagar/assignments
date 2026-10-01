# Seat Reservation Service

Spring Boot JSON API for reserving assigned seats. PostgreSQL is the application's only database.

## Option 1: Run against Neon

Set these environment variables in IntelliJ (or your shell) using your Neon connection details:

```bash
DATABASE_URL=jdbc:postgresql://<neon-host>/<database>?sslmode=require
DATABASE_USERNAME=<neon-user>
DATABASE_PASSWORD=<neon-password>
ADMIN_TOKEN=token
```

Then start `PaytmAssignmentApplication` from IntelliJ or run:

```bash
./mvnw spring-boot:run
```

Neon credentials belong in local environment settings only; never commit them.

## Option 2: Run completely locally

```bash
docker compose up --build
```

This starts PostgreSQL and the app. The local PostgreSQL container uses the development-only `seatdb` / `seatuser` / `seatpassword` settings and stores data in a named volume. The app is available at `http://localhost:8080`; if that port is occupied, run `APP_PORT=8081 docker compose up --build` and use `http://localhost:8081`.

Compose supplies a local-only admin token (`local-compose-admin-token`) by default. It is not used by Render.

## Build

```bash
./mvnw clean package
```

This compiles and packages the application without requiring a running database.

## One-command burst test

`burst.sh` creates a fresh show and test users, then checks a concurrent hot-seat storm, same-key idempotency and mismatched-body handling, the per-user limit, owner-only cancellation and seat rebooking, final seat-count reconciliation, and Prometheus metrics. It prints the HTTP outcome counts and exits non-zero if a correctness assertion fails.

Run it against the local Compose service:

```bash
./burst.sh http://localhost:8080
```

For these examples, configure the app with the admin token value `token`. Start Compose with that value:

```bash
ADMIN_TOKEN=token docker compose up --build
```

Then run the burst script:

```bash
ADMIN_TOKEN=token ./burst.sh http://localhost:8080
```

For Render, set the service's `ADMIN_TOKEN` environment variable to `token`, then run:

```bash
export ADMIN_TOKEN=token
./burst.sh https://paytm-seat-reservation-qiq4.onrender.com
```

The script waits for `/health/ready`; it requires `bash`, `curl`, `python3`, and `xargs`. Each run creates a new show and multiple user accounts, so use a test deployment and expect those records to remain in the database. The hot-seat test starts one worker per `HOT_USERS` request (500 by default); a machine or hosting plan with strict process/connection limits may need a smaller test size:

```bash
HOT_USERS=50 LIMIT_USERS=8 ./burst.sh http://localhost:8080
```

Success ends with `DONE show_id=...`, one `201` and the rest `409` for the hot seat, zero `5xx`, and a passing reconciliation invariant. The script prints a metrics snapshot as well. A failed assertion or unavailable service exits non-zero.

## Environment variables

| Variable | Purpose |
|---|---|
| `DATABASE_URL` | JDBC URL, e.g. `jdbc:postgresql://localhost:5432/seatdb` |
| `DATABASE_USERNAME` | PostgreSQL username |
| `DATABASE_PASSWORD` | PostgreSQL password |
| `PORT` | HTTP port; defaults to `8080` |
| `ADMIN_TOKEN` | Required admin bearer token; Compose supplies a local-only development value |
| `APP_PORT` | Optional host port for Compose; defaults to `8080` |

## API endpoints

| Method | Endpoint | Purpose |
|---|---|---|
| `POST` | `/shows` | Create a show (admin) |
| `POST` | `/shows/{id}/reserve` | Reserve seat(s) (authenticated user) |
| `POST` | `/reservations/{id}/cancel` | Cancel your reservation |
| `GET` | `/shows/{id}` | Read seat states and reconciliation counts |
| `POST` | `/auth/users` | Create a user and bearer token |
| `GET` | `/health/live` | Liveness |
| `GET` | `/health/ready` | Readiness; checks PostgreSQL |
| `GET` | `/actuator/prometheus` | Prometheus metrics |
| `GET` | `/logs` | Recent sanitized reservation/business events |

`POST /shows` requires the admin bearer token. Reserve, get-reservation, and cancel endpoints require a valid user bearer token; cancel and get-reservation enforce ownership. `GET /shows/{id}`, health, and metrics are public for evaluator access, and `POST /auth/users` is public so clients can obtain a user token. Missing or invalid credentials on protected routes return 401; a valid non-admin token creating a show returns 403. Reserve requests are all-or-nothing. Reservation identity and cancellation ownership come from the bearer token, not request-body fields. More design detail is in [WRITEUP.md](WRITEUP.md).

## Metrics + Logs

### Metrics

Live endpoint: `https://paytm-seat-reservation-qiq4.onrender.com/actuator/prometheus`

It exposes confirmed reservations, declined reservations by reason, and an available-seat gauge queried from the current PostgreSQL seat state.

```bash
curl http://localhost:8080/actuator/prometheus
```

### Logs

Live endpoint: `https://paytm-seat-reservation-qiq4.onrender.com/logs`

This returns up to 500 recent sanitized reservation/business events, newest first. The default response contains the latest 100 events; use `?limit=500` for the maximum.

```bash
curl http://localhost:8080/logs
```

Events include timestamp, request ID, event type, show/user/reservation IDs when available, seats, and HTTP status. The endpoint does not expose raw application logs or authentication/database credentials.

## Deployment

Deploy the Dockerfile as a Render Web Service and connect it to a Neon PostgreSQL database. Set `DATABASE_URL` to a JDBC-form PostgreSQL URL (for Neon, include `?sslmode=require`), and set `DATABASE_USERNAME`, `DATABASE_PASSWORD`, and `ADMIN_TOKEN=token` in Render's environment settings. The app has no default admin token and will not start unless one is configured. Render provides `PORT`; the app listens on it. `token` is a simple assignment/demo value; use a strong unique value for any non-demo deployment.
