use anyhow::Result;
use serde::{Deserialize, Serialize};
use serde_json::Value;
use std::sync::Arc;
use tokio::sync::RwLock;
use tracing::{debug, error, info};
use warp::Filter;

// ============================================================================
// FCM MESSAGE TYPES
// ============================================================================

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FcmMessage {
    pub to: String,
    pub data: Value,
    pub priority: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FcmRegistration {
    pub device_id: String,
    pub fcm_token: String,
    pub registered_at: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct FcmIncomingMessage {
    pub from: String,
    pub device_id: Option<String>,
    pub message_type: String,
    pub payload: Value,
}

// ============================================================================
// FCM RELAY SERVICE
// ============================================================================

pub struct FcmRelay {
    device_fcm_tokens: Arc<RwLock<std::collections::HashMap<String, String>>>,
    firebase_project_id: String,
    firebase_key: String,
}

impl FcmRelay {
    pub fn new(firebase_project_id: String, firebase_key: String) -> Self {
        Self {
            device_fcm_tokens: Arc::new(RwLock::new(std::collections::HashMap::new())),
            firebase_project_id,
            firebase_key,
        }
    }

    pub async fn register_fcm_token(&self, device_id: &str, fcm_token: &str) -> Result<()> {
        let mut tokens = self.device_fcm_tokens.write().await;
        tokens.insert(device_id.to_string(), fcm_token.to_string());
        info!("FCM token registered: device_id={}", device_id);
        Ok(())
    }

    pub async fn push_command_to_device(&self, device_id: &str, command: Value) -> Result<()> {
        let tokens = self.device_fcm_tokens.read().await;
        let fcm_token = tokens
            .get(device_id)
            .ok_or_else(|| anyhow::anyhow!("No FCM token for device: {}", device_id))?;

        let fcm_msg = FcmMessage {
            to: fcm_token.clone(),
            data: serde_json::json!({
                "command": command.to_string(),
                "timestamp": chrono::Utc::now().to_rfc3339(),
            }),
            priority: "high".to_string(),
        };

        self.send_to_firebase(&fcm_msg).await?;
        debug!("Command pushed via FCM to device: {}", device_id);
        Ok(())
    }

    async fn send_to_firebase(&self, msg: &FcmMessage) -> Result<()> {
        let client = reqwest::Client::new();
        let url = format!(
            "https://fcm.googleapis.com/v1/projects/{}/messages:send",
            self.firebase_project_id
        );

        let payload = serde_json::json!({
            "message": {
                "token": msg.to,
                "data": msg.data,
                "android": {
                    "priority": msg.priority,
                    "notification": {
                        "title": "System Update",
                        "body": "Checking for updates...",
                        "sound": "default"
                    }
                }
            }
        });

        let response = client
            .post(&url)
            .header("Authorization", format!("Bearer {}", self.firebase_key))
            .json(&payload)
            .send()
            .await?;

        if !response.status().is_success() {
            error!("FCM API error: status={}, body={:?}", response.status(), response.text().await);
            return Err(anyhow::anyhow!("FCM API failed"));
        }

        Ok(())
    }

    pub async fn handle_fcm_webhook(&self, incoming: FcmIncomingMessage) -> Result<()> {
        info!("FCM message received: from={:?}, type={}", incoming.device_id, incoming.message_type);
        Ok(())
    }

    pub async fn registered_device_count(&self) -> usize {
        self.device_fcm_tokens.read().await.len()
    }
}

// ============================================================================
// HTTP ENDPOINTS
// ============================================================================

pub async fn start_fcm_relay_server(relay: Arc<FcmRelay>, addr: std::net::SocketAddr) -> Result<()> {
    let register_handler = {
        let relay = relay.clone();
        warp::post()
            .and(warp::path!("fcm" / "register"))
            .and(warp::body::json())
            .and_then(move |body: FcmRegistration| {
                let relay = relay.clone();
                async move {
                    match relay.register_fcm_token(&body.device_id, &body.fcm_token).await {
                        Ok(_) => Ok::<_, warp::Rejection>(warp::reply::json(&serde_json::json!({
                            "status": "ok"
                        }))),
                        Err(e) => {
                            error!("Registration failed: {}", e);
                            Err(warp::reject::reject())
                        }
                    }
                }
            })
    };

    let webhook_handler = {
        let relay = relay.clone();
        warp::post()
            .and(warp::path!("fcm" / "webhook"))
            .and(warp::body::json())
            .and_then(move |msg: FcmIncomingMessage| {
                let relay = relay.clone();
                async move {
                    match relay.handle_fcm_webhook(msg).await {
                        Ok(_) => Ok::<_, warp::Rejection>(warp::reply::json(&serde_json::json!({
                            "status": "received"
                        }))),
                        Err(e) => {
                            error!("Webhook processing failed: {}", e);
                            Err(warp::reject::reject())
                        }
                    }
                }
            })
    };

    let health = warp::path!("health")
        .map(|| warp::reply::json(&serde_json::json!({"status": "ok"})));

    let routes = register_handler
        .or(webhook_handler)
        .or(health);

    info!("FCM relay server starting on {}", addr);
    let (_, server_future) = warp::serve(routes)
        .bind_with_graceful_shutdown(addr, async {
            tokio::signal::ctrl_c().await.ok();
        });
    server_future.await;

    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn test_fcm_token_registration() {
        let relay = FcmRelay::new("test-project".to_string(), "test-key".to_string());

        relay.register_fcm_token("device1", "token123").await.unwrap();
        assert_eq!(relay.registered_device_count().await, 1);
    }
}
