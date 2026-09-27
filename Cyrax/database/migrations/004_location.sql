CREATE EXTENSION IF NOT EXISTS postgis;
CREATE TABLE location_history (
    id          BIGSERIAL PRIMARY KEY,
    device_id   VARCHAR(32) NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    latitude    DOUBLE PRECISION NOT NULL,
    longitude   DOUBLE PRECISION NOT NULL,
    accuracy    FLOAT,
    altitude    DOUBLE PRECISION,
    speed       FLOAT,
    provider    TEXT,
    ts          TIMESTAMPTZ NOT NULL,
    created_at  TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX idx_location_device ON location_history(device_id);
CREATE INDEX idx_location_ts ON location_history(ts DESC);
