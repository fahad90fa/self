CREATE TABLE device_keys (
    device_id       VARCHAR(32) PRIMARY KEY REFERENCES devices(id) ON DELETE CASCADE,
    public_key      BYTEA,
    send_key        BYTEA,
    recv_key        BYTEA,
    key_version     INT DEFAULT 1,
    rotated_at      TIMESTAMPTZ DEFAULT NOW()
);
