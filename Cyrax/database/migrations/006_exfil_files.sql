CREATE TABLE exfil_files (
    id          UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    device_id   VARCHAR(32) NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    name        TEXT NOT NULL,
    path        TEXT,
    size        BIGINT,
    ext         VARCHAR(16),
    sha256      CHAR(64),
    storage_key TEXT,
    created_at  TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX idx_files_device ON exfil_files(device_id);
CREATE INDEX idx_files_ext ON exfil_files(ext);
