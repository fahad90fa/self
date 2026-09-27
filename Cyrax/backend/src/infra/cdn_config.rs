// [context: Rust, Linux/x64, Cloudflare CDN fronting config management]

use reqwest::Client;
use serde::{Deserialize, Serialize};
use serde_json::json;
use thiserror::Error;

#[derive(Error, Debug)]
pub enum CdnError {
    #[error("http error: {0}")]
    Http(#[from] reqwest::Error),
    #[error("api error: {0}")]
    Api(String),
}

#[derive(Debug, Serialize, Deserialize)]
pub struct DnsRecord {
    pub id: String,
    pub name: String,
    pub content: String,
    pub r#type: String,
    pub proxied: bool,
}

pub struct CloudflareConfig {
    client: Client,
    api_token: String,
    base_url: String,
}

impl CloudflareConfig {
    pub fn new(api_token: String) -> Self {
        Self {
            client: Client::new(),
            api_token,
            base_url: "https://api.cloudflare.com/client/v4".to_string(),
        }
    }

    fn auth_header(&self) -> String {
        format!("Bearer {}", self.api_token)
    }

    pub async fn update_dns_record(
        &self,
        zone_id: &str,
        record_id: &str,
        name: &str,
        ip: &str,
    ) -> Result<(), CdnError> {
        let url = format!("{}/zones/{}/dns_records/{}", self.base_url, zone_id, record_id);
        let resp = self.client
            .put(&url)
            .header("Authorization", self.auth_header())
            .json(&json!({ "type": "A", "name": name, "content": ip, "proxied": true }))
            .send()
            .await?;
        let body: serde_json::Value = resp.json().await?;
        if body.get("success").and_then(|v| v.as_bool()).unwrap_or(false) {
            Ok(())
        } else {
            Err(CdnError::Api(body.to_string()))
        }
    }

    pub async fn purge_cache(&self, zone_id: &str, urls: &[String]) -> Result<(), CdnError> {
        let url = format!("{}/zones/{}/purge_cache", self.base_url, zone_id);
        let resp = self.client
            .post(&url)
            .header("Authorization", self.auth_header())
            .json(&json!({ "files": urls }))
            .send()
            .await?;
        let body: serde_json::Value = resp.json().await?;
        if body.get("success").and_then(|v| v.as_bool()).unwrap_or(false) {
            Ok(())
        } else {
            Err(CdnError::Api(body.to_string()))
        }
    }

    pub async fn rotate_origin_ip(
        &self,
        zone_id: &str,
        record_id: &str,
        new_ip: &str,
    ) -> Result<(), CdnError> {
        self.update_dns_record(zone_id, record_id, "@", new_ip).await
    }

    pub async fn enable_ddos_protection(&self, zone_id: &str) -> Result<(), CdnError> {
        let url = format!("{}/zones/{}/settings/security_level", self.base_url, zone_id);
        let resp = self.client
            .patch(&url)
            .header("Authorization", self.auth_header())
            .json(&json!({ "value": "high" }))
            .send()
            .await?;
        let body: serde_json::Value = resp.json().await?;
        if body.get("success").and_then(|v| v.as_bool()).unwrap_or(false) {
            Ok(())
        } else {
            Err(CdnError::Api(body.to_string()))
        }
    }

    pub async fn list_dns_records(&self, zone_id: &str) -> Result<Vec<DnsRecord>, CdnError> {
        let url = format!("{}/zones/{}/dns_records", self.base_url, zone_id);
        let resp = self.client
            .get(&url)
            .header("Authorization", self.auth_header())
            .send()
            .await?;
        let body: serde_json::Value = resp.json().await?;
        let records = serde_json::from_value(
            body.get("result").cloned().unwrap_or_default()
        ).unwrap_or_default();
        Ok(records)
    }
}
