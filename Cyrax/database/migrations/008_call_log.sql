CREATE TABLE call_log (
    id          BIGSERIAL PRIMARY KEY,
    device_id   VARCHAR(32) NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    number      TEXT,
    name        TEXT,
    call_type   INT,
    duration    BIGINT,
    ts          TIMESTAMPTZ NOT NULL,
    created_at  TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX idx_calls_device ON call_log(device_id);
CREATE INDEX idx_calls_ts ON call_log(ts DESC);
