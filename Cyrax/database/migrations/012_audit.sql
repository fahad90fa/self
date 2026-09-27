CREATE TABLE audit_log (
    id          BIGSERIAL PRIMARY KEY,
    operator    TEXT,
    action      TEXT NOT NULL,
    target      TEXT,
    detail      JSONB,
    ip          INET,
    created_at  TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX idx_audit_operator ON audit_log(operator);
CREATE INDEX idx_audit_ts ON audit_log(created_at DESC);
