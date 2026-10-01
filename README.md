# Seat Reservation Service

Spring Boot JSON API for reserving assigned seats. PostgreSQL is the application's only database.

## Option 1: Run against Neon

Set these environment variables in IntelliJ (or your shell) using your Neon connection details:

```bash
DATABASE_URL=jdbc:postgresql://<neon-host>/<database>?sslmode=require
DATABASE_USERNAME=<neon-user>
DATABASE_PASSWORD=<neon-password>
ADMIN_TOKEN=<unique-admin-token>
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

This compiles and packages the application without requiring a running database. The repository does not currently include automated JUnit tests; use the burst script against a running service for API and concurrency checks.

## Run the burst test

With the local app running:

```bash
./burst.sh http://localhost:8080
```

It exercises concurrent hot-seat reservations, concurrent same-key retries, the per-user limit, owner-only cancellation and seat release, token-derived identity, metrics, and final seat reconciliation. If you override `ADMIN_TOKEN` when starting Compose, export that same value before running the script.

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

`POST /shows` requires the admin bearer token. Reserve, get-reservation, and cancel endpoints require a valid user bearer token; cancel and get-reservation enforce ownership. `GET /shows/{id}`, health, and metrics are public for evaluator access, and `POST /auth/users` is public so clients can obtain a user token. Missing or invalid credentials on protected routes return 401; a valid non-admin token creating a show returns 403. Reserve requests are all-or-nothing. Reservation identity and cancellation ownership come from the bearer token, not request-body fields. More design detail is in [WRITEUP.md](WRITEUP.md).

## Deployment

Deploy the Dockerfile as a Render Web Service and connect it to a Neon PostgreSQL database. Set `DATABASE_URL` to a JDBC-form PostgreSQL URL (for Neon, include `?sslmode=require`), and set `DATABASE_USERNAME`, `DATABASE_PASSWORD`, and a unique `ADMIN_TOKEN` in Render's environment settings. The app has no default admin token and will not start unless one is configured. Render provides `PORT`; the app listens on it.
