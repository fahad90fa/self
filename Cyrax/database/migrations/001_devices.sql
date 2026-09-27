CREATE TABLE devices (
    id              VARCHAR(32) PRIMARY KEY,
    campaign_id     VARCHAR(36),
    model           TEXT,
    manufacturer    TEXT,
    android_version INT,
    sdk             INT,
    battery         INT DEFAULT 0,
    ip              INET,
    country         CHAR(2),
    accessibility   BOOLEAN DEFAULT FALSE,
    is_rooted       BOOLEAN DEFAULT FALSE,
    last_seen       TIMESTAMPTZ DEFAULT NOW(),
    created_at      TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX idx_devices_campaign ON devices(campaign_id);
CREATE INDEX idx_devices_last_seen ON devices(last_seen);
