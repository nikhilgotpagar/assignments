-- PostgreSQL schema

CREATE TABLE users (
    id              UUID PRIMARY KEY,
    token           VARCHAR(64) NOT NULL UNIQUE,
    display_name    VARCHAR(128) NOT NULL,
    role            VARCHAR(32)  NOT NULL DEFAULT 'USER',
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE shows (
    id                  UUID PRIMARY KEY,
    name                VARCHAR(256) NOT NULL,
    price_paise         BIGINT       NOT NULL,
    per_user_limit      INT          NOT NULL DEFAULT 4,
    total_seats         INT          NOT NULL,
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE seats (
    id               UUID PRIMARY KEY,
    show_id          UUID         NOT NULL,
    seat_label       VARCHAR(32)  NOT NULL,
    status           VARCHAR(16)  NOT NULL DEFAULT 'AVAILABLE',
    reservation_id   UUID,
    updated_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_show_seat UNIQUE (show_id, seat_label),
    CONSTRAINT fk_seats_show FOREIGN KEY (show_id) REFERENCES shows (id)
);

CREATE INDEX idx_seats_show_status ON seats (show_id, status);
CREATE INDEX idx_seats_reservation ON seats (reservation_id);

CREATE TABLE reservations (
    id                UUID PRIMARY KEY,
    show_id           UUID         NOT NULL,
    user_id           UUID         NOT NULL,
    amount_paise      BIGINT       NOT NULL,
    status            VARCHAR(16)  NOT NULL,
    idempotency_key   VARCHAR(128) NOT NULL,
    request_hash      VARCHAR(64)  NOT NULL,
    seat_labels_json  VARCHAR(4000) NOT NULL,
    created_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at        TIMESTAMP WITH TIME ZONE,
    cancelled_at      TIMESTAMP WITH TIME ZONE,
    CONSTRAINT uq_idempotency UNIQUE (user_id, show_id, idempotency_key),
    CONSTRAINT fk_reservations_show FOREIGN KEY (show_id) REFERENCES shows (id),
    CONSTRAINT fk_reservations_user FOREIGN KEY (user_id) REFERENCES users (id)
);

CREATE INDEX idx_reservations_show_user ON reservations (show_id, user_id);
CREATE INDEX idx_reservations_expiry ON reservations (status, expires_at);
