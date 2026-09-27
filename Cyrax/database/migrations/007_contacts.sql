CREATE TABLE contacts (
    id          BIGSERIAL PRIMARY KEY,
    device_id   VARCHAR(32) NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    name        TEXT,
    phones      JSONB DEFAULT '[]',
    emails      JSONB DEFAULT '[]',
    raw         JSONB,
    created_at  TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX idx_contacts_device ON contacts(device_id);
