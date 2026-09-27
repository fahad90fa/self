CREATE TABLE campaigns (
    id             UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    name           TEXT NOT NULL,
    target_country CHAR(2),
    dga_seed       BYTEA,
    config_json    JSONB DEFAULT '{}',
    created_at     TIMESTAMPTZ DEFAULT NOW()
);
