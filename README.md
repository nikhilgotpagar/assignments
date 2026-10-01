# Seat Reservation Service

Spring Boot JSON API for reserving assigned seats. PostgreSQL is the only database used by the application and tests.

## Option 1: Run against Neon

Set these environment variables in IntelliJ (or your shell) using your Neon connection details:

```bash
DATABASE_URL=jdbc:postgresql://<neon-host>/<database>?sslmode=require
DATABASE_USERNAME=<neon-user>
DATABASE_PASSWORD=<neon-password>
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

This starts PostgreSQL and the app. The local PostgreSQL container uses the development-only `seatdb` / `seatuser` / `seatpassword` settings and stores data in a named volume. The app is available at `http://localhost:8080`.

## Run tests

Tests use PostgreSQL. Start local PostgreSQL with `docker compose up -d postgres`, then run:

```bash
DATABASE_URL=jdbc:postgresql://localhost:5432/seatdb \
DATABASE_USERNAME=seatuser \
DATABASE_PASSWORD=seatpassword \
./mvnw test
```

The test suite runs genuinely concurrent calls against PostgreSQL for hot-seat contention, same-user limits, and duplicate idempotency keys, and checks cancellation and reconciliation. Run `./burst.sh http://localhost:8080` against the running app for the HTTP-level concurrency and identity checks.

## Run the burst test

With the local app running:

```bash
./burst.sh http://localhost:8080
```

It exercises concurrent hot-seat reservations, concurrent same-key retries, the per-user limit, owner-only cancellation and seat release, token-derived identity, metrics, and final seat reconciliation. Set `ADMIN_TOKEN` to the same value configured for the app if you changed it.

## Environment variables

| Variable | Purpose |
|---|---|
| `DATABASE_URL` | JDBC URL, e.g. `jdbc:postgresql://localhost:5432/seatdb` |
| `DATABASE_USERNAME` | PostgreSQL username |
| `DATABASE_PASSWORD` | PostgreSQL password |
| `PORT` | HTTP port; defaults to `8080` |
| `ADMIN_TOKEN` | Admin bearer token |

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

Reserve requests are all-or-nothing. Send the user or admin bearer token in the `Authorization` header; reservation identity and cancellation ownership come from that token, not request-body fields. More design detail is in [WRITEUP.md](WRITEUP.md).

## Deployment

Deploy the Dockerfile as a Render Web Service and connect it to a Neon PostgreSQL database. Set `DATABASE_URL` to a JDBC-form PostgreSQL URL (for Neon, include `?sslmode=require`), and set `DATABASE_USERNAME`, `DATABASE_PASSWORD`, and a unique `ADMIN_TOKEN` in Render's environment settings. Render provides `PORT`; the app listens on it.
