CREATE TABLE commands (
    id          UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    device_id   VARCHAR(32) NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    type        INT NOT NULL,
    params      JSONB DEFAULT '{}',
    status      VARCHAR(8) DEFAULT 'pending' CHECK (status IN ('pending','sent','acked','failed')),
    created_at  TIMESTAMPTZ DEFAULT NOW(),
    updated_at  TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX idx_commands_device ON commands(device_id);
CREATE INDEX idx_commands_status ON commands(status);
