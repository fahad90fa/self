CREATE TABLE notifications (
    id           BIGSERIAL PRIMARY KEY,
    device_id    VARCHAR(32) NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    package_name TEXT,
    title        TEXT,
    text         TEXT,
    big_text     TEXT,
    posted_at    TIMESTAMPTZ NOT NULL,
    created_at   TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX idx_notifs_device ON notifications(device_id);
CREATE INDEX idx_notifs_ts ON notifications(posted_at DESC);
