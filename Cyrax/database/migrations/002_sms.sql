CREATE TABLE sms_messages (
    id          BIGSERIAL PRIMARY KEY,
    device_id   VARCHAR(32) NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    address     TEXT,
    body        TEXT,
    direction   VARCHAR(8) CHECK (direction IN ('incoming','outgoing')),
    ts          TIMESTAMPTZ NOT NULL,
    created_at  TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX idx_sms_device ON sms_messages(device_id);
CREATE INDEX idx_sms_ts ON sms_messages(ts DESC);
