CREATE TABLE IF NOT EXISTS comparison_scope (
    concert_id BIGINT NOT NULL,
    schedule_id BIGINT NOT NULL,
    active_count BIGINT NOT NULL DEFAULT 0 CHECK (active_count >= 0),
    PRIMARY KEY (concert_id, schedule_id)
);
CREATE TABLE IF NOT EXISTS comparison_hold (
    concert_id BIGINT NOT NULL,
    schedule_id BIGINT NOT NULL,
    seat_id BIGINT NOT NULL,
    user_id VARCHAR(128) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (concert_id, schedule_id, seat_id)
);
CREATE INDEX IF NOT EXISTS comparison_hold_owner
    ON comparison_hold (concert_id, schedule_id, user_id, expires_at);
CREATE TABLE IF NOT EXISTS comparison_queue (
    concert_id BIGINT NOT NULL,
    schedule_id BIGINT NOT NULL,
    user_id VARCHAR(128) COLLATE "C" NOT NULL,
    score BIGINT NOT NULL,
    heartbeat_expires_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (concert_id, schedule_id, user_id)
);
CREATE INDEX IF NOT EXISTS comparison_queue_order
    ON comparison_queue (concert_id, schedule_id, score, user_id);
CREATE TABLE IF NOT EXISTS comparison_token (
    token VARCHAR(36) PRIMARY KEY,
    payload VARCHAR(256) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL
);
