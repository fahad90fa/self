// [context: Rust, Linux/x64, Telegram/Discord webhook relay]

use reqwest::Client;
use serde_json::json;
use thiserror::Error;

#[derive(Error, Debug)]
pub enum WebhookError {
    #[error("http error: {0}")]
    Http(#[from] reqwest::Error),
    #[error("not configured")]
    NotConfigured,
}

#[derive(Clone)]
pub struct WebhookConfig {
    pub telegram_bot_token: Option<String>,
    pub telegram_chat_id: Option<String>,
    pub discord_webhook_url: Option<String>,
}

#[derive(Clone)]
pub struct WebhookRelay {
    client: Client,
    config: WebhookConfig,
}

impl WebhookRelay {
    pub fn new(config: WebhookConfig) -> Self {
        Self { client: Client::new(), config }
    }

    pub async fn notify_new_device(&self, device_info: &serde_json::Value) -> Result<(), WebhookError> {
        let model = device_info.get("model").and_then(|v| v.as_str()).unwrap_or("unknown");
        let country = device_info.get("country").and_then(|v| v.as_str()).unwrap_or("??");
        let msg = format!("🟢 New device enrolled\nModel: {}\nCountry: {}", model, country);
        self.broadcast(&msg).await
    }

    pub async fn notify_data_received(
        &self,
        device_id: &str,
        data_type: &str,
        size: usize,
    ) -> Result<(), WebhookError> {
        let msg = format!("📦 Data: {} | Type: {} | Size: {}B", device_id, data_type, size);
        self.broadcast(&msg).await
    }

    pub async fn send_to_telegram(&self, msg: &str) -> Result<(), WebhookError> {
        let token = self.config.telegram_bot_token.as_deref().ok_or(WebhookError::NotConfigured)?;
        let chat_id = self.config.telegram_chat_id.as_deref().ok_or(WebhookError::NotConfigured)?;
        let url = format!("https://api.telegram.org/bot{}/sendMessage", token);
        self.client
            .post(&url)
            .json(&json!({ "chat_id": chat_id, "text": msg, "parse_mode": "HTML" }))
            .send()
            .await?;
        Ok(())
    }

    pub async fn send_to_discord(&self, msg: &str) -> Result<(), WebhookError> {
        let url = self.config.discord_webhook_url.as_deref().ok_or(WebhookError::NotConfigured)?;
        self.client
            .post(url)
            .json(&json!({ "content": msg }))
            .send()
            .await?;
        Ok(())
    }

    async fn broadcast(&self, msg: &str) -> Result<(), WebhookError> {
        let _ = self.send_to_telegram(msg).await;
        let _ = self.send_to_discord(msg).await;
        Ok(())
    }
}
